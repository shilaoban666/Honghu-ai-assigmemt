package com.honghu.ai.assigment.skill.builtin.tools;

import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.repository.UserRepository;
import com.honghu.ai.assigment.skill.builtin.annotation.NativeSkill;
import com.honghu.ai.assigment.skill.builtin.annotation.NativeTool;
import com.honghu.ai.assigment.skill.core.DangerLevel;
import com.honghu.ai.assigment.skill.core.ToolExecutionContext;
import com.honghu.ai.assigment.skill.core.ToolExecutionContextHolder;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向模型暴露可信用户/会话上下文的隐藏内置技能。
 *
 * <p>这类身份上下文能力不应该像普通能力一样展示在输入框胶囊中，但运行时又经常需要它：模型可能需要知道
 * 当前用户角色、会话 id 或消息 id 来解释工具行为。因此它是后端运行时底座能力，前端可以过滤隐藏。</p>
 *
 * <p>本工具只返回非敏感信息，不返回密码、token、API Key、密钥配置或其他凭证。</p>
 */
@NativeSkill(
        key = "user_context",
        displayName = "用户上下文",
        description = "读取当前可信用户、角色和会话上下文",
        icon = "CTX",
        category = "系统",
        defaultEnabled = true,
        mandatory = true,
        requiredRole = User.UserRole.USER)
@RequiredArgsConstructor
public class UserContextTool {

    /** 用户仓库，用于根据上下文 userId 读取角色、用户名和昵称。 */
    private final UserRepository userRepository;

    /**
     * 返回当前调用者的非敏感上下文。
     *
     * @return 模型可读取的用户、会话和消息上下文
     */
    @NativeTool(
            name = "current",
            description = "返回当前可信用户、角色、会话和消息上下文。不会返回密码、token 或 API Key。",
            dangerLevel = DangerLevel.SAFE)
    public Map<String, Object> current() {
        // 从 ThreadLocal 读取工具执行链注入的可信上下文；没有上下文时按匿名处理。
        ToolExecutionContext context = ToolExecutionContextHolder.get();
        // 上下文可能为空，userId 也可能为空。
        String userId = context == null ? null : context.userId();
        // 只有 userId 非空时才查数据库；查不到也按 guest 返回。
        User user = userId == null || userId.isBlank()
                ? null
                : userRepository.findById(userId).orElse(null);

        // 使用 LinkedHashMap 保持返回字段顺序稳定。
        Map<String, Object> result = new LinkedHashMap<>();
        // 当前可信用户 id，匿名时可能为空。
        result.put("userId", userId);
        // 当前会话 id，来自后端 ToolContext。
        result.put("sessionId", context == null ? null : context.sessionId());
        // 触发工具调用的消息 id，可能为空。
        result.put("messageId", context == null ? null : context.messageId());
        // 用户角色；查不到用户时回退 GUEST。
        result.put("role", user == null ? User.UserRole.GUEST.name() : user.getUserRole().name());
        // 登录名，非敏感但可用于个性化回答。
        result.put("username", user == null ? null : user.getUsername());
        // 昵称，非敏感展示字段。
        result.put("nickname", user == null ? null : user.getNickname());
        // 直接给模型一个布尔字段，避免模型自己根据 role 判断匿名状态。
        result.put("guest", user == null || user.getUserRole() == User.UserRole.GUEST);
        return result;
    }
}
