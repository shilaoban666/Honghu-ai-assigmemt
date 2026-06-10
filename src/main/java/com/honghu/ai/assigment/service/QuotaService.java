package com.honghu.ai.assigment.service;

import com.honghu.ai.assigment.dto.QuotaCheckResult;
import com.honghu.ai.assigment.dto.QuotaSnapshot;
import com.honghu.ai.assigment.entity.AiUsageEvent;
import com.honghu.ai.assigment.entity.PlanEntitlement;
import com.honghu.ai.assigment.entity.RoleQuotaConfig;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.entity.UserQuotaOverride;
import com.honghu.ai.assigment.entity.Workspace;
import com.honghu.ai.assigment.repository.AiUsageEventRepository;
import com.honghu.ai.assigment.repository.PlanEntitlementRepository;
import com.honghu.ai.assigment.repository.RoleQuotaConfigRepository;
import com.honghu.ai.assigment.repository.UserQuotaOverrideRepository;
import com.honghu.ai.assigment.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * AI 配额服务。
 *
 * <p>它负责两类事情：</p>
 * <ul>
 *     <li>调用前检查：当前用户/团队是否还能继续发起 AI 请求。</li>
 *     <li>查询快照：把当前日/月已用额度、上限、标准 token 折算结果返回给前端或后台。</li>
 * </ul>
 * <p>真实拦截口径仍然是金额（CNY 成本），标准 token 只是展示层统一口径。</p>
 */
@Service
@RequiredArgsConstructor
public class QuotaService {

    /**
     * 标准 token 折算基准。
     *
     * <p>金额配额仍是最终拦截依据，但后台更适合展示统一 token。这里约定 1 CNY
     * 折算成 1,000,000 标准 token。高级模型因为同样原始 token 产生更高 cost，
     * 会折算出更多标准 token 消耗，看起来就是“消耗倍率更高”。</p>
     */
    private static final BigDecimal STANDARD_TOKENS_PER_CNY = new BigDecimal("1000000");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final UserRepository userRepository;
    private final RoleQuotaConfigRepository roleQuotaConfigRepository;
    private final PlanEntitlementRepository planEntitlementRepository;
    private final UserQuotaOverrideRepository userQuotaOverrideRepository;
    private final AiUsageEventRepository aiUsageEventRepository;
    private final WorkspaceContextService workspaceContextService;

    /**
     * AI 调用前的配额检查入口。
     *
     * <p>当前版本采用“软拦截”策略：调用前只检查已经成功产生的费用是否达到上限，
     * 不做预扣和退款。这样流式调用更简单，第一版也更稳定；代价是触顶那一次调用可能略微超额，
     * 下一次调用才会被 429 拦截。</p>
     *
     * <p>个人和企业的口径不同：</p>
     * <ul>
     *     <li>个人空间：按 {@code user_id + workspace_id} 聚合用量，额度来自 {@code role_quota_config}。</li>
     *     <li>企业空间：按 {@code workspace_id} 聚合团队共享用量，额度来自 {@code plan_entitlement}。</li>
     * </ul>
     *
     * @param userId 调用用户；为空时视为匿名调用，当前直接放行
     * @param workspaceId workspace ID；为空时使用用户默认 workspace
     * @return 是否允许调用，以及当前用量、上限、重置时间
     */
    public QuotaCheckResult checkBeforeCall(String userId, String workspaceId) {
        if (!StringUtils.hasText(userId)) {
            return QuotaCheckResult.allowed(BigDecimal.ZERO, null, BigDecimal.ZERO, null, nextDayStart());
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        Workspace workspace = workspaceContextService.resolveWorkspace(userId, workspaceId);
        LimitPair limits = resolveLimits(user, workspace);
        if (limits.unlimited()) {
            return QuotaCheckResult.allowed(BigDecimal.ZERO, null, BigDecimal.ZERO, null, nextDayStart());
        }

        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        BigDecimal dailyUsed = usedCost(userId, workspace, dayStart);
        BigDecimal monthlyUsed = usedCost(userId, workspace, monthStart);
        if (limits.dailyLimit() != null && dailyUsed.compareTo(limits.dailyLimit()) >= 0) {
            return QuotaCheckResult.blocked("DAILY_LIMIT_EXCEEDED", dailyUsed, limits.dailyLimit(), monthlyUsed, limits.monthlyLimit(), nextDayStart());
        }
        if (limits.monthlyLimit() != null && monthlyUsed.compareTo(limits.monthlyLimit()) >= 0) {
            return QuotaCheckResult.blocked("MONTHLY_LIMIT_EXCEEDED", dailyUsed, limits.dailyLimit(), monthlyUsed, limits.monthlyLimit(), nextMonthStart());
        }
        return QuotaCheckResult.allowed(dailyUsed, limits.dailyLimit(), monthlyUsed, limits.monthlyLimit(), nextDayStart());
    }

    /**
     * 查询配额快照，供后台和用户自查使用。
     *
     * <p>这个方法不改变任何状态，只按当前时间窗口实时 SUM 用量。
     * 日窗口从当天 00:00 开始，月窗口从当月 1 日 00:00 开始。</p>
     *
     * @param userId 用户 ID
     * @param workspaceId workspace ID；为空时使用默认 workspace
     * @param period DAILY 或 MONTHLY，其他值按 DAILY 处理
     * @return 当前时间窗口的已用额度和上限
     */
    public QuotaSnapshot getSnapshot(String userId, String workspaceId, String period) {
        if (!StringUtils.hasText(userId)) {
            return QuotaSnapshot.builder().period(period).used(BigDecimal.ZERO).unlimited(true).build();
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        Workspace workspace = workspaceContextService.resolveWorkspace(userId, workspaceId);
        LimitPair limits = resolveLimits(user, workspace);
        boolean monthly = "MONTHLY".equalsIgnoreCase(period);
        LocalDateTime from = monthly ? LocalDate.now().withDayOfMonth(1).atStartOfDay() : LocalDate.now().atStartOfDay();
        BigDecimal used = usedCost(userId, workspace, from);
        BigDecimal rawTokenUsed = usedTokens(userId, workspace, from);
        BigDecimal limit = monthly ? limits.monthlyLimit() : limits.dailyLimit();
        BigDecimal tokenUsed = moneyToStandardTokens(used);
        BigDecimal tokenLimit = moneyToStandardTokens(limit);
        BigDecimal tokenRemaining = tokenLimit == null ? null : tokenLimit.subtract(tokenUsed).max(BigDecimal.ZERO);
        BigDecimal usagePercent = tokenLimit == null || tokenLimit.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : tokenUsed.multiply(ONE_HUNDRED).divide(tokenLimit, 2, RoundingMode.HALF_UP).min(ONE_HUNDRED);
        return QuotaSnapshot.builder()
                .userId(userId)
                .workspaceId(workspace == null ? null : workspace.getWorkspaceId())
                .period(monthly ? "MONTHLY" : "DAILY")
                .used(used)
                .limit(limit)
                .moneyUsed(used)
                .moneyLimit(limit)
                .rawTokenUsed(rawTokenUsed)
                .tokenUsed(tokenUsed)
                .tokenLimit(tokenLimit)
                .tokenRemaining(tokenRemaining)
                .usagePercent(usagePercent)
                .unlimited(limits.unlimited())
                .build();
    }

    /**
     * 将金额额度折算成后台展示用的标准 token。
     *
     * <p>这个方法只做展示换算，不改变真实配额拦截逻辑。真实拦截仍看 CNY 成本，
     * 因此模型价格越高，消耗同样原始 token 时折算出来的标准 token 越多。</p>
     */
    public BigDecimal moneyToStandardTokens(BigDecimal money) {
        if (money == null) {
            return null;
        }
        return money.multiply(STANDARD_TOKENS_PER_CNY).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 将已产生费用折算成标准 token。
     *
     * <p>等价于“这个调用消耗了多少配额 token”。DeepSeek 等低价模型倍率低，
     * Claude Opus / GPT 高价模型倍率高。</p>
     */
    public BigDecimal costToStandardTokens(BigDecimal cost) {
        return moneyToStandardTokens(cost == null ? BigDecimal.ZERO : cost);
    }

    /**
     * 解析当前上下文的日/月额度上限。
     *
     * <p>workspace 有 planCode 时优先按团队套餐取额度；没有 planCode 时按用户角色取额度。
     * 最后再叠加未过期的用户额度 delta。delta 是增量，不是覆盖绝对值。</p>
     */
    private LimitPair resolveLimits(User user, Workspace workspace) {
        BigDecimal dailyLimit;
        BigDecimal monthlyLimit;
        if (workspace != null && StringUtils.hasText(workspace.getPlanCode())) {
            dailyLimit = findPlanLimit(workspace.getPlanCode(), "DAILY_BUDGET");
            monthlyLimit = findPlanLimit(workspace.getPlanCode(), "MONTHLY_BUDGET");
        } else {
            User.UserRole role = user.getUserRole() == null ? User.UserRole.GUEST : user.getUserRole();
            RoleQuotaConfig config = roleQuotaConfigRepository.findById(role.name()).orElse(null);
            dailyLimit = config == null ? null : config.getDailyLimit();
            monthlyLimit = config == null ? null : config.getMonthlyLimit();
        }
        List<UserQuotaOverride> overrides = userQuotaOverrideRepository.findActiveOverrides(
                user.getUserId(),
                workspace == null ? null : workspace.getWorkspaceId(),
                LocalDateTime.now()
        );
        for (UserQuotaOverride override : overrides) {
            dailyLimit = addNullable(dailyLimit, override.getDailyDelta());
            monthlyLimit = addNullable(monthlyLimit, override.getMonthlyDelta());
        }
        return new LimitPair(dailyLimit, monthlyLimit);
    }

    /**
     * 从套餐权益中读取一个 LIMIT 类型的数值。
     *
     * <p>常见 key 包括 DAILY_BUDGET、MONTHLY_BUDGET、CONCURRENT_REQUESTS。
     * 当前方法只读取金额类预算，单位是 CNY。</p>
     */
    private BigDecimal findPlanLimit(String planCode, String key) {
        return planEntitlementRepository.findByPlanCodeAndEntitlementTypeAndEntitlementKeyAndEnabledTrue(
                        planCode,
                        PlanEntitlement.EntitlementType.LIMIT,
                        key)
                .map(PlanEntitlement::getValueNumber)
                .orElse(null);
    }

    /**
     * 按当前上下文统计已经成功扣费的金额。
     *
     * <p>只有 {@link AiUsageEvent.Status#SUCCESS} 会计入额度消耗。
     * 失败、超限拦截、缺价格拦截都会落流水，但费用为 0，不参与额度扣减。</p>
     */
    private BigDecimal usedCost(String userId, Workspace workspace, LocalDateTime from) {
        if (workspace != null && StringUtils.hasText(workspace.getPlanCode())) {
            return aiUsageEventRepository.sumSuccessfulCostByWorkspace(workspace.getWorkspaceId(), AiUsageEvent.Status.SUCCESS.name(), from);
        }
        return aiUsageEventRepository.sumSuccessfulCostByUser(userId, workspace == null ? null : workspace.getWorkspaceId(), AiUsageEvent.Status.SUCCESS.name(), from);
    }

    /** 按当前上下文统计真实原始 token，用于后台和审计对照。 */
    private BigDecimal usedTokens(String userId, Workspace workspace, LocalDateTime from) {
        if (workspace != null && StringUtils.hasText(workspace.getPlanCode())) {
            return aiUsageEventRepository.sumSuccessfulTokensByWorkspace(workspace.getWorkspaceId(), AiUsageEvent.Status.SUCCESS.name(), from);
        }
        return aiUsageEventRepository.sumSuccessfulTokensByUser(userId, workspace == null ? null : workspace.getWorkspaceId(), AiUsageEvent.Status.SUCCESS.name(), from);
    }

    /**
     * 对“有限额度”做增量叠加。
     *
     * <p>如果 base 为 null，代表“不限额”，这时无论 delta 是多少都仍然返回 null，
     * 因为无限额度不应该被补偿记录意外改成有限额度。</p>
     */
    private BigDecimal addNullable(BigDecimal base, BigDecimal delta) {
        if (base == null) {
            return null;
        }
        return base.add(delta == null ? BigDecimal.ZERO : delta);
    }

    /** 返回下一天 00:00，给日额度超限提示做恢复时间。 */
    private LocalDateTime nextDayStart() {
        return LocalDate.now().plusDays(1).atStartOfDay();
    }

    /** 返回下个月 1 日 00:00，给月额度超限提示做恢复时间。 */
    private LocalDateTime nextMonthStart() {
        return LocalDate.now().plusMonths(1).withDayOfMonth(1).atTime(LocalTime.MIN);
    }

    /**
     * 日/月额度二元组。
     *
     * <p>两个上限都为 null 表示无限额度，例如 ADMIN 或 VIP。
     * 如果只有其中一个为 null，则表示对应周期不限，另一个周期仍然受限。</p>
     */
    private record LimitPair(BigDecimal dailyLimit, BigDecimal monthlyLimit) {
        boolean unlimited() {
            return dailyLimit == null && monthlyLimit == null;
        }
    }
}
