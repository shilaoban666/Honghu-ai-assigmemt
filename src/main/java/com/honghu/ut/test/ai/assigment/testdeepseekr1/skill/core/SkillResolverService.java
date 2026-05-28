package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.BuiltinSkillProvider;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.SessionSkillSetting;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.entity.Skill;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SessionSkillSettingRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.SkillRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.repository.UserSkillInstallRepository;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 会话工具集解析服务。
 *
 * <p>模型每轮对话可以使用哪些工具，不能只看全局技能列表，还需要综合用户角色、必装技能、
 * 新会话默认启用项、用户已安装技能以及当前会话的显式开关。这个服务集中实现这些规则，
 * 并把最终技能集合转换为 Spring AI 可注入的 {@link ToolCallbackProvider}。</p>
 *
 * <p>当前实现已经接入内置技能的解析链路：{@link Skill} 元数据来自数据库，
 * {@code BuiltinSkillProvider} 把 BUILTIN 类型技能转换为 {@link ResolvedTool}，
 * {@link ToolExecutorService} 负责真正执行并落审计日志。MCP 和 CUSTOM 来源后续可以按相同模式
 * 增加 provider，而不需要改变会话规则计算逻辑。</p>
 */
@Service
@RequiredArgsConstructor
public class SkillResolverService {
    // 技能定义表，负责读取 skill 的来源、默认启用、必装、权限等元数据。
    private final SkillRepository skillRepository;
    // 用户安装表，负责读取某个用户额外安装并启用的 MCP/CUSTOM/内置技能。
    private final UserSkillInstallRepository userSkillInstallRepository;
    // 会话级开关表，负责读取“当前会话显式启用了哪些技能”。
    private final SessionSkillSettingRepository sessionSkillSettingRepository;
    // 用户表，用来把 userId 解析成角色，后续按角色过滤技能。
    private final UserRepository userRepository;
    // 内置技能 Provider，负责把 BUILTIN 类型的 skill 转成可执行的 ResolvedTool。
    private final BuiltinSkillProvider builtinSkillProvider;
    // 工具执行服务，真正通过反射调用 Java 方法并落调用日志。
    private final ToolExecutorService toolExecutorService;
    // Jackson 用来把模型传来的 Map 参数序列化成 JSON，再交给统一执行器解析。
    private final ObjectMapper objectMapper;

    /**
     * 解析某个用户在某个会话中可用的运行时工具列表。
     *
     * @param userId 当前用户 ID，允许为空；为空或无效时按访客权限处理
     * @param sessionId 当前会话 ID，允许为空；为空时不会应用会话级显式开关
     * @return 已绑定执行对象的工具列表
     */
    public List<ResolvedTool> resolveToolsForSession(String userId, String sessionId) {
        // 先根据 userId 得到当前调用者角色；游客或查不到用户时会降级为 GUEST。
        User.UserRole role = currentRole(userId);
        // 再按“必装 + 会话显式设置 + 默认启用 + 用户安装”规则算出本轮允许注入的 skillId。
        Set<Long> enabledSkillIds = computeEnabledSkillIds(userId, sessionId, role);
        if (enabledSkillIds.isEmpty()) {
            // 没有任何技能时直接返回空列表，避免后面做无意义的数据库和 Provider 分发。
            return List.of();
        }
        // 根据 id 批量读取技能定义；这里拿到的是完整 Skill 对象，后续要按 source 分发。
        List<Skill> skills = skillRepository.findAllById(enabledSkillIds);
        Map<SkillSource, List<Skill>> bySource = skills.stream()
                // enabled=false 是管理员级全局下线，哪怕会话启用了也不能注入给模型。
                .filter(Skill::isEnabled)
                .collect(Collectors.groupingBy(Skill::getSource));
        List<ResolvedTool> tools = new ArrayList<>();
        // 当前阶段先接 BUILTIN；MCP/CUSTOM Provider 后续补齐时按同样模式追加到 tools。
        tools.addAll(builtinSkillProvider.toolsFor(bySource.getOrDefault(SkillSource.BUILTIN, List.of())));
        return tools;
    }

    /**
     * 解析并包装为 Spring AI 可以直接注入 ChatModel 的工具回调提供器。
     *
     * @param userId 当前用户 ID
     * @param sessionId 当前会话 ID
     * @return Spring AI 工具回调提供器；没有工具时返回空 provider
     */
    public ToolCallbackProvider resolveToolCallbacksForSession(String userId, String sessionId) {
        // Spring AI 最终需要 ToolCallbackProvider，所以这里把内部 ResolvedTool 统一包装成 FunctionToolCallback。
        return ToolCallbackProvider.from(
                resolveToolsForSession(userId, sessionId).stream()
                        .map(this::toFunctionCallback)
                        .toList());
    }

    /**
     * 根据会话规则计算最终启用的技能 ID 集合。
     *
     * <p>优先级为：必装技能始终补回；如果会话已有显式设置，则以会话设置为准；
     * 否则使用角色允许范围内的默认启用技能，并合并用户已安装且启用的技能。</p>
     *
     * @param userId 当前用户 ID，用于读取用户安装关系
     * @param sessionId 当前会话 ID，用于读取会话级开关
     * @param role 当前用户角色
     * @return 最终应注入的技能 ID 集合
     */
    public Set<Long> computeEnabledSkillIds(String userId, String sessionId, User.UserRole role) {
        // mandatory 是硬规则：只要角色允许且技能全局启用，就必须进入工具集，前端也不应该允许关闭。
        Set<Long> mandatory = new HashSet<>(skillRepository.findMandatoryEnabledIds(allowedRoles(role)));
        if (sessionId != null && !sessionId.isBlank()) {
            // 如果当前会话已经保存过显式设置，则以会话设置为准，避免默认技能在老会话中反复“自动冒出来”。
            List<SessionSkillSetting> settings = sessionSkillSettingRepository.findBySessionId(sessionId);
            if (!settings.isEmpty()) {
                Set<Long> explicit = settings.stream()
                        // 只收集 enabled=true 的记录；enabled=false 表示用户在当前会话明确关闭过。
                        .filter(SessionSkillSetting::isEnabled)
                        .map(SessionSkillSetting::getSkillId)
                        .collect(Collectors.toSet());
                // 必装技能无条件补回，防止用户通过 API 绕过 UI 把 mandatory 技能关掉。
                explicit.addAll(mandatory);
                return explicit;
            }
        }
        // 没有会话显式设置时，使用系统默认启用技能作为新会话初始工具集。
        Set<Long> defaults = new HashSet<>(skillRepository.findDefaultEnabledIds(allowedRoles(role)));
        if (userId != null && !userId.isBlank()) {
            // 用户已安装且启用的技能也加入默认集合，例如用户安装过 Tavily 后新会话可以直接使用。
            defaults.addAll(userSkillInstallRepository.findEnabledSkillIdsByUser(userId));
        }
        // 最后再次补 mandatory，保证默认集合和用户安装集合都不能覆盖必装规则。
        defaults.addAll(mandatory);
        return defaults;
    }

    private User.UserRole currentRole(String userId) {
        if (userId == null || userId.isBlank()) {
            // 无 userId 的场景包括简单聊天、匿名访问和内部调用，统一按 GUEST 权限处理。
            return User.UserRole.GUEST;
        }
        return userRepository.findById(userId)
                // 查到用户就使用数据库中的真实角色，避免信任前端传来的 role。
                .map(User::getUserRole)
                // userId 无效时不抛异常，降级 GUEST 可以让匿名能力继续工作。
                .orElse(User.UserRole.GUEST);
    }

    private Set<User.UserRole> allowedRoles(User.UserRole role) {
        if (role == null) {
            role = User.UserRole.GUEST;
        }
        // 角色按权限从低到高排序；高角色天然包含低角色可用技能。
        List<User.UserRole> order = List.of(
                User.UserRole.GUEST,
                User.UserRole.USER,
                User.UserRole.PRO,
                User.UserRole.PLUS,
                User.UserRole.PRO_PLUS,
                User.UserRole.VIP,
                User.UserRole.ADMIN
        );
        int idx = order.indexOf(role);
        if (idx < 0) {
            // 未知角色按最低权限处理，避免新枚举值未配置时意外获得高级技能。
            idx = 0;
        }
        EnumSet<User.UserRole> allowed = EnumSet.noneOf(User.UserRole.class);
        for (int i = 0; i <= idx; i++) {
            // 例如 VIP 会得到 GUEST/USER/PRO/PLUS/PRO_PLUS/VIP 这些 required_role 的技能。
            allowed.add(order.get(i));
        }
        return allowed;
    }

    private ToolCallback toFunctionCallback(ResolvedTool tool) {
        return FunctionToolCallback.builder(tool.qualifiedName(), (Map<String, Object> input, ToolContext context) -> {
                    try {
                        // Spring AI 传入的是 Map；执行器内部统一吃 JSON 字符串，所以先序列化一次。
                        String args = objectMapper.writeValueAsString(input == null ? Map.of() : input);
                        // ToolContext 里可能携带 user/session/message/query，用于工具内部授权和日志归因。
                        ToolExecutionContext executionContext = new ToolExecutionContext(
                                context == null ? null : stringValue(context, "userId"),
                                context == null ? null : stringValue(context, "sessionId"),
                                context == null ? null : longValue(context, "messageId"),
                                context == null ? null : stringValue(context, "query")
                        );
                        Object result = toolExecutorService.execute(tool, args, executionContext);
                        return result;
                    } catch (JsonProcessingException e) {
                        // 参数序列化失败说明工具调用链已经无法继续，抛 IllegalStateException 让上层按模型调用失败处理。
                        throw new IllegalStateException("Unable to serialize tool arguments for " + tool.qualifiedName(), e);
                    }
                })
                .build();
    }

    private String stringValue(ToolContext context, String key) {
        // ToolContext 的 value 类型不固定，这里统一转字符串，避免调用端传 Long/UUID 时类型不匹配。
        Object value = context.getContext().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(ToolContext context, String key) {
        // messageId 是日志字段，可以不存在；不存在时返回 null，不阻塞工具执行。
        Object value = context.getContext().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            // 已经是数字类型时直接取 longValue，避免字符串 parse 的额外风险。
            return number.longValue();
        }
        try {
            // 有些调用链会把 messageId 放成字符串，这里做兼容解析。
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            // messageId 解析失败不应该导致工具调用失败，所以返回 null 只影响日志关联精度。
            return null;
        }
    }
}
