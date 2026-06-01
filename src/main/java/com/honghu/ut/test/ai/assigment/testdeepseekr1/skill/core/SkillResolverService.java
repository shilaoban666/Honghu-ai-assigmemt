package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.BuiltinSkillProvider;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.cli.CliSkillProvider;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.SessionSkillSetting;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.Skill;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.mcp.McpSkillProvider;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SessionSkillSettingRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SkillRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.ToolInvocationLogRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.UserSkillInstallRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.security.SkillAccessPolicy;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.security.ToolGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 为某个用户/会话解析最终“模型可见工具集”。
 *
 * <p>本服务负责策略计算，不负责工具具体执行。它会把必装技能、会话级显式开关、默认启用技能和用户已安装技能
 * 合并成最终启用的 {@link Skill} 集合，然后按来源分发给不同 provider。每个 provider 返回
 * {@link ToolCallbackRegistration}，本解析器再统一包上 {@link AuditingToolCallback}。这个装饰器就是
 * BUILTIN、MCP、CLI 工具共同的审计和风险控制边界。</p>
 *
 * <p>解析顺序有意义：先解析内置工具，因为 time/math/RAG 等运行时辅助能力应该稳定存在；再解析远程 MCP；
 * 最后解析 CLI，因为 CLI 工具危险等级高，通常会被审批守卫拒绝到用户确认后才执行。</p>
 */
@Service
@RequiredArgsConstructor
public class SkillResolverService {

    /** 全局技能目录。 */
    private final SkillRepository skillRepository;

    /** 用户安装表，用于读取已安装技能和用户级运行配置。 */
    private final UserSkillInstallRepository userSkillInstallRepository;

    /** 会话覆盖表，用于读取某个会话里的显式开关。 */
    private final SessionSkillSettingRepository sessionSkillSettingRepository;

    /** 用户仓库，用于根据可信 userId 读取角色。 */
    private final UserRepository userRepository;

    /** 共享角色阶梯策略。 */
    private final SkillAccessPolicy skillAccessPolicy;

    /** 内置 Java 反射工具 provider。 */
    private final BuiltinSkillProvider builtinSkillProvider;

    /** 远程 HTTP JSON-RPC MCP provider。 */
    private final McpSkillProvider mcpSkillProvider;

    /** 本地白名单 CLI provider。 */
    private final CliSkillProvider cliSkillProvider;

    /** 内置 Java 工具反射执行器。 */
    private final ToolExecutorService toolExecutorService;

    /** 由审计装饰器调用的危险等级守卫。 */
    private final ToolGuard toolGuard;

    /** 审计装饰器写调用日志时使用的仓库。 */
    private final ToolInvocationLogRepository toolInvocationLogRepository;

    /** 工具参数序列化和审计参数规范化共用的 JSON 工具。 */
    private final ObjectMapper objectMapper;

    /**
     * 解析可执行的内置 Java 工具。
     *
     * <p>这个方法主要为了兼容旧测试或直接检查内置工具的调用方。完整运行时应优先使用
     * {@link #resolveToolCallbacksForSession(String, String)}，因为 MCP 和 CLI provider 返回的是
     * callback，而不是 {@link ResolvedTool}。</p>
     *
     * @param userId 可信用户 id 或开发兜底用户 id
     * @param sessionId 聊天会话 id
     * @return 已解析的内置 Java 工具
     */
    public List<ResolvedTool> resolveToolsForSession(String userId, String sessionId) {
        // 先根据 userId 得到角色，后续所有默认/必装技能查询都要按角色过滤。
        User.UserRole role = currentRole(userId);
        // 计算当前用户/会话最终启用的 skillId。
        Set<Long> enabledSkillIds = computeEnabledSkillIds(userId, sessionId, role);
        if (enabledSkillIds.isEmpty()) {
            // 没有启用技能时直接返回空列表。
            return List.of();
        }
        // 按来源分组；这里只取 BUILTIN。
        Map<SkillSource, List<Skill>> bySource = enabledSkillsBySource(enabledSkillIds);
        return builtinSkillProvider.toolsFor(bySource.getOrDefault(SkillSource.BUILTIN, List.of()));
    }

    /**
     * 解析所有应该注入给模型的工具 callback。
     *
     * @param userId 可信用户 id 或开发兜底用户 id
     * @param sessionId 聊天会话 id
     * @return 包含审计装饰器 callback 的 Spring AI ToolCallbackProvider
     */
    public ToolCallbackProvider resolveToolCallbacksForSession(String userId, String sessionId) {
        // 角色决定哪些 requiredRole 的技能能被当前用户使用。
        User.UserRole role = currentRole(userId);
        // 计算最终启用 skillId，逻辑和前端 CapabilityService 复用同一个方法。
        Set<Long> enabledSkillIds = computeEnabledSkillIds(userId, sessionId, role);
        if (enabledSkillIds.isEmpty()) {
            // Spring AI 接受空 provider，表示本轮不注入任何工具。
            return ToolCallbackProvider.from(List.of());
        }

        // 按来源分发给不同 provider。
        Map<SkillSource, List<Skill>> bySource = enabledSkillsBySource(enabledSkillIds);
        List<ToolCallbackRegistration> registrations = new ArrayList<>();
        // BUILTIN 先解析为 ResolvedTool，再包装成 Spring AI FunctionToolCallback。
        registrations.addAll(builtinSkillProvider.toolsFor(bySource.getOrDefault(SkillSource.BUILTIN, List.of()))
                .stream()
                .map(this::toBuiltinRegistration)
                .toList());
        // MCP provider 会根据用户安装配置去远程 tools/list，并生成 callback。
        registrations.addAll(mcpSkillProvider.callbacksFor(bySource.getOrDefault(SkillSource.MCP, List.of()), userId));
        // CLI provider 只生成危险工具 callback，后续 ToolGuard 默认拦截。
        registrations.addAll(cliSkillProvider.callbacksFor(bySource.getOrDefault(SkillSource.CLI, List.of())));

        // 所有来源的 callback 都统一包 AuditingToolCallback，确保审计和风险守卫一致。
        List<ToolCallback> auditedCallbacks = registrations.stream()
                .map(registration -> new AuditingToolCallback(registration, toolGuard, toolInvocationLogRepository, objectMapper))
                .map(ToolCallback.class::cast)
                .toList();
        return ToolCallbackProvider.from(auditedCallbacks);
    }

    /**
     * 计算某个会话最终启用的 skillId 集合。
     *
     * <p>优先级：mandatory 技能永远加入；如果会话存在显式设置，则以会话设置为准，再把 mandatory 补回；
     * 如果会话没有显式设置，则合并 defaultEnabled 技能和用户已安装技能，最后再补 mandatory。</p>
     *
     * @param userId 当前用户 id
     * @param sessionId 当前会话 id
     * @param role 当前可信角色
     * @return 最终启用的 skillId 集合
     */
    public Set<Long> computeEnabledSkillIds(String userId, String sessionId, User.UserRole role) {
        // 先查询当前角色可用的 mandatory 技能，它们后续无论如何都会加入。
        Set<Long> mandatory = new HashSet<>(skillRepository.findMandatoryEnabledIds(skillAccessPolicy.allowedRequiredRoles(role)));
        if (sessionId != null && !sessionId.isBlank()) {
            // 会话有 id 时才可能存在显式开关记录。
            List<SessionSkillSetting> settings = sessionSkillSettingRepository.findBySessionId(sessionId);
            if (!settings.isEmpty()) {
                // 一旦存在任意会话设置，就把该会话视为有“显式快照”。
                Set<Long> explicit = settings.stream()
                        // 只保留 enabled=true 的设置；enabled=false 表示显式关闭。
                        .filter(SessionSkillSetting::isEnabled)
                        .map(SessionSkillSetting::getSkillId)
                        .collect(Collectors.toSet());
                // mandatory 不能被会话开关真正关闭，所以补回。
                explicit.addAll(mandatory);
                return explicit;
            }
        }

        // 没有会话显式设置时，从当前角色可用的 defaultEnabled 技能开始。
        Set<Long> defaults = new HashSet<>(skillRepository.findDefaultEnabledIds(skillAccessPolicy.allowedRequiredRoles(role)));
        if (userId != null && !userId.isBlank() && !"guest".equals(userId)) {
            // 登录用户再叠加自己安装且启用的能力。
            defaults.addAll(userSkillInstallRepository.findEnabledSkillIdsByUser(userId));
        }
        // 最后无条件补 mandatory。
        defaults.addAll(mandatory);
        return defaults;
    }

    /**
     * 根据可信 userId 返回用户角色。
     *
     * @param userId 用户 id；null/blank/guest 都按访客处理
     * @return 数据库中的角色；用户不存在时返回 GUEST
     */
    public User.UserRole currentRole(String userId) {
        if (userId == null || userId.isBlank() || "guest".equals(userId)) {
            return User.UserRole.GUEST;
        }
        return userRepository.findById(userId)
                .map(User::getUserRole)
                .orElse(User.UserRole.GUEST);
    }

    /**
     * 按技能来源对已启用技能分组。
     */
    private Map<SkillSource, List<Skill>> enabledSkillsBySource(Set<Long> enabledSkillIds) {
        return skillRepository.findAllById(enabledSkillIds).stream()
                // 二次过滤全局 enabled=false，防止已安装但被管理员下线的能力被注入。
                .filter(Skill::isEnabled)
                .collect(Collectors.groupingBy(Skill::getSource));
    }

    /**
     * 把一个内置 ResolvedTool 包装成 Spring AI callback 注册信息。
     */
    private ToolCallbackRegistration toBuiltinRegistration(ResolvedTool tool) {
        ToolCallback callback = FunctionToolCallback.builder(tool.qualifiedName(), (Map<String, Object> input, ToolContext context) -> {
                    try {
                        // Spring AI 传入的是 Map 参数，内置执行器期望 JSON 字符串，所以先序列化。
                        String args = objectMapper.writeValueAsString(input == null ? Map.of() : input);
                        // 从 ToolContext 提取可信上下文，交给内置执行器写入 ThreadLocal。
                        ToolExecutionContext executionContext = new ToolExecutionContext(
                                context == null ? null : stringValue(context, "userId"),
                                context == null ? null : stringValue(context, "sessionId"),
                                context == null ? null : longValue(context, "messageId"),
                                context == null ? null : stringValue(context, "query")
                        );
                        return toolExecutorService.execute(tool, args, executionContext);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Unable to serialize tool arguments for " + tool.qualifiedName(), e);
                    }
                })
                // description 和 inputSchema 都来自启动期同步到数据库的工具定义。
                .description(tool.description())
                .inputSchema(tool.parametersSchema().toString())
                .build();
        return new ToolCallbackRegistration(
                callback,
                tool.skillId(),
                tool.skillKey(),
                tool.qualifiedName(),
                tool.dangerLevel(),
                "BUILTIN"
        );
    }

    /**
     * 从 ToolContext 中读取字符串字段。
     */
    private String stringValue(ToolContext context, String key) {
        Object value = context.getContext().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 从 ToolContext 中宽松读取 Long 字段。
     */
    private Long longValue(ToolContext context, String key) {
        Object value = context.getContext().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
