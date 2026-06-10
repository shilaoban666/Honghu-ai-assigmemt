package com.honghu.ai.assigment.service;

import com.honghu.ai.assigment.entity.AiModelDefinition;
import com.honghu.ai.assigment.entity.PlanEntitlement;
import com.honghu.ai.assigment.entity.RoleModelDefault;
import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.entity.UserModelPermission;
import com.honghu.ai.assigment.entity.Workspace;
import com.honghu.ai.assigment.repository.AiModelDefinitionRepository;
import com.honghu.ai.assigment.repository.PlanEntitlementRepository;
import com.honghu.ai.assigment.repository.RoleModelDefaultRepository;
import com.honghu.ai.assigment.repository.UserModelPermissionRepository;
import com.honghu.ai.assigment.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型权益计算服务。
 *
 * <p>“某个用户此刻到底能用哪些模型”这个问题，现在统一由这个类回答。
 * 它会综合四层信息：</p>
 * <ol>
 *     <li>用户角色默认模型</li>
 *     <li>企业套餐 plan 权益</li>
 *     <li>workspace 上下文</li>
 *     <li>用户级单独覆盖（GRANT / DENY）</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final UserRepository userRepository;
    private final AiModelDefinitionRepository aiModelDefinitionRepository;
    private final RoleModelDefaultRepository roleModelDefaultRepository;
    private final PlanEntitlementRepository planEntitlementRepository;
    private final UserModelPermissionRepository userModelPermissionRepository;
    private final WorkspaceContextService workspaceContextService;

    /**
     * 查询用户在默认 workspace 下可使用的模型。
     *
     * <p>这是兼容旧代码的入口。旧业务只传 {@link User}，没有显式 workspace 概念；
     * 新权限体系要求所有真实用户都有一个默认 workspace，所以这里会先解析默认 workspace，
     * 再进入统一的模型权益计算流程。</p>
     *
     * <p>如果用户为空、没有 userId、用户不存在，或者显式是 GUEST，统一按游客处理。
     * 这样可以避免匿名访问时因为找不到 workspace 直接报错。</p>
     *
     * @param user 当前用户，可以为空
     * @return 按 level、score、displayName 排序后的可用模型列表
     */
    public List<AiModelDefinition> listAccessibleModels(User user) {
        if (user == null || !StringUtils.hasText(user.getUserId())) {
            return listByRole(User.UserRole.GUEST);
        }
        if (user.getUserRole() == User.UserRole.GUEST || !userRepository.existsById(user.getUserId())) {
            return listByRole(User.UserRole.GUEST);
        }
        Workspace workspace = workspaceContextService.resolveWorkspace(user.getUserId(), null);
        return listAccessibleModels(user, workspace);
    }

    /**
     * 查询用户在指定 workspace 下可使用的模型。
     *
     * <p>企业场景中，同一个用户可能同时属于个人空间和企业空间。
     * 前端通过 {@code X-Workspace-Id} 或请求体里的 workspaceId 切换上下文，
     * 后端必须根据 workspace 来决定模型白名单和配额池。</p>
     *
     * @param userId 用户 ID
     * @param workspaceId workspace ID；为空时使用用户默认 workspace
     * @return 当前上下文实际可用模型
     */
    public List<AiModelDefinition> listAccessibleModels(String userId, String workspaceId) {
        User user = StringUtils.hasText(userId)
                ? userRepository.findById(userId).orElse(null)
                : null;
        if (user == null) {
            return listByRole(User.UserRole.GUEST);
        }
        Workspace workspace = workspaceContextService.resolveWorkspace(userId, workspaceId);
        return listAccessibleModels(user, workspace);
    }

    /**
     * 根据“用户 + workspace”计算最终模型列表。
     *
     * <p>基础规则有两种：</p>
     * <ul>
     *     <li>workspace 有 planCode：说明这是企业/团队套餐，基础模型来自 {@code plan_entitlement}。</li>
     *     <li>workspace 没有 planCode：说明是个人空间，基础模型来自 {@code role_model_default}。</li>
     * </ul>
     *
     * <p>基础规则算完后，再叠加用户级覆盖。覆盖规则固定为：
     * {@code DENY > GRANT > base rules}。也就是说，即使角色或套餐允许某模型，
     * 只要用户被单独 DENY，就不会出现在最终列表里。</p>
     *
     * @param user 当前用户
     * @param workspace 已校验成员关系的 workspace；为空时按用户角色处理
     * @return 与模型目录 enabled 状态取交集后的模型列表
     */
    public List<AiModelDefinition> listAccessibleModels(User user, Workspace workspace) {
        Set<String> baseCodes = new LinkedHashSet<>();
        if (workspace != null && StringUtils.hasText(workspace.getPlanCode())) {
            baseCodes.addAll(resolvePlanModelCodes(workspace.getPlanCode()));
        } else {
            baseCodes.addAll(roleModelDefaultRepository.findByRoleAndEnabledTrue(resolveRole(user).name()).stream()
                    .map(RoleModelDefault::getModelCode)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }

        applyUserOverrides(baseCodes, user, workspace);
        if (baseCodes.isEmpty()) {
            return List.of();
        }
        return aiModelDefinitionRepository.findByModelCodeInAndEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc(baseCodes);
    }

    /**
     * 只按角色查询默认模型列表。
     *
     * <p>这个方法主要给后台角色配置页、游客兜底和少量兼容逻辑使用。
     * 它不会叠加用户 override，也不会处理企业 plan。</p>
     *
     * @param role 平台角色；为空时按 GUEST 处理
     * @return 该角色默认可用模型
     */
    public List<AiModelDefinition> listByRole(User.UserRole role) {
        Set<String> roleCodes = roleModelDefaultRepository.findByRoleAndEnabledTrue((role == null ? User.UserRole.GUEST : role).name())
                .stream()
                .map(RoleModelDefault::getModelCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (roleCodes.isEmpty()) {
            return List.of();
        }
        return aiModelDefinitionRepository.findByModelCodeInAndEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc(roleCodes);
    }

    /**
     * 解析套餐允许的模型编码。
     *
     * <p>套餐权益支持两种白名单：</p>
     * <ul>
     *     <li>{@code ALLOWED_MODEL}: 精确开放某个模型，例如 gpt-5.4。</li>
     *     <li>{@code ALLOWED_PROVIDER}: 开放某个 provider 下的所有模型，例如 deepseek；特殊值 {@code *} 表示开放全部 provider。</li>
     * </ul>
     *
     * @param planCode 套餐编码
     * @return 套餐允许的模型编码集合
     */
    private Set<String> resolvePlanModelCodes(String planCode) {
        List<AiModelDefinition> enabledModels = aiModelDefinitionRepository.findByEnabledTrueOrderByLevelAscScoreDescDisplayNameAsc();
        Set<String> allowedModels = planEntitlementRepository
                .findByPlanCodeAndEntitlementTypeAndEnabledTrue(planCode, PlanEntitlement.EntitlementType.ALLOWED_MODEL)
                .stream()
                .map(PlanEntitlement::getEntitlementKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> allowedProviders = planEntitlementRepository
                .findByPlanCodeAndEntitlementTypeAndEnabledTrue(planCode, PlanEntitlement.EntitlementType.ALLOWED_PROVIDER)
                .stream()
                .map(PlanEntitlement::getEntitlementKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return enabledModels.stream()
                .filter(model -> allowedModels.contains(model.getModelCode())
                        || allowedProviders.contains("*")
                        || allowedProviders.contains(model.getProviderCode()))
                .map(AiModelDefinition::getModelCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 应用用户级模型覆盖。
     *
     * <p>这里故意分两轮处理：第一轮先处理 GRANT，第二轮再处理 DENY。
     * 这样即使同一用户在全局和 workspace 维度同时存在覆盖，也能保证 DENY 最终生效。</p>
     */
    private void applyUserOverrides(Set<String> baseCodes, User user, Workspace workspace) {
        if (user == null || !StringUtils.hasText(user.getUserId())) {
            return;
        }
        String workspaceId = workspace == null ? null : workspace.getWorkspaceId();
        List<UserModelPermission> overrides = userModelPermissionRepository.findActiveOverrides(
                user.getUserId(),
                workspaceId,
                LocalDateTime.now()
        );
        for (UserModelPermission override : overrides) {
            if (!"DENY".equalsIgnoreCase(override.getOverrideType())) {
                baseCodes.add(override.getModelCode());
            }
        }
        for (UserModelPermission override : overrides) {
            if ("DENY".equalsIgnoreCase(override.getOverrideType())) {
                baseCodes.remove(override.getModelCode());
            }
        }
    }

    /**
     * 解析用户的“平台角色”。
     *
     * <p>这里返回的是平台层角色，而不是 workspace 内成员角色。
     * 当用户为空或角色为空时，统一回落到 GUEST，保证权限计算总有一个明确起点。</p>
     */
    private User.UserRole resolveRole(User user) {
        return user == null || user.getUserRole() == null ? User.UserRole.GUEST : user.getUserRole();
    }
}
