package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.tools;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.ChatMessage;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.ChatMessageRepository;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation.NativeSkill;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation.NativeTool;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.builtin.annotation.ToolParam;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.DangerLevel;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.ToolExecutionContext;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core.ToolExecutionContextHolder;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读访问当前会话最近消息的内置技能。
 *
 * <p>模型有时需要回顾较早的对话内容，但又不应该在每次请求里都塞入完整历史。这个工具提供按需查询最近消息的能力。
 * 它只从可信上下文读取当前 sessionId，绝不接受模型传入的 sessionId，因此不能被模型用来查询其他会话。</p>
 */
@NativeSkill(
        key = "session",
        displayName = "会话历史",
        description = "查询当前会话最近消息和上下文摘要",
        icon = "CHAT",
        category = "业务",
        defaultEnabled = true,
        mandatory = false,
        requiredRole = User.UserRole.USER)
@RequiredArgsConstructor
public class SessionHistoryTool {

    /** 聊天消息仓库，用于按 sessionId 读取消息历史。 */
    private final ChatMessageRepository chatMessageRepository;

    /**
     * 返回当前会话最近若干条消息。
     *
     * @param limit 最多返回多少条；为空默认 10，最大 30
     * @return 按时间正序排列的最近消息
     */
    @NativeTool(
            name = "recent_messages",
            description = "读取当前会话最近消息。只读，不允许指定其他 sessionId。",
            dangerLevel = DangerLevel.SAFE)
    public Map<String, Object> recentMessages(
            @ToolParam(description = "最多返回多少条消息，默认 10，最大 30。", required = false) Double limit) {
        // 会话历史必须绑定当前会话上下文。
        ToolExecutionContext context = requireContext();
        // 对 limit 做边界限制，避免模型一次请求过多历史。
        int safeLimit = limit == null ? 10 : Math.max(1, Math.min(30, limit.intValue()));
        // 先按创建时间正序读取当前会话全部消息。
        List<ChatMessage> all = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(context.sessionId());
        // 计算最近 safeLimit 条的起始下标。
        int from = Math.max(0, all.size() - safeLimit);
        // 截取最近消息，并把实体转换成模型可读的简单 Map。
        List<Map<String, Object>> messages = all.subList(from, all.size()).stream()
                .map(message -> {
                    // 每条消息保持固定字段顺序。
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("chatId", message.getChatId());
                    row.put("role", message.getChatRole());
                    row.put("content", message.getContent());
                    // 时间转成字符串，避免模型收到 Java 时间对象结构。
                    row.put("createdAt", message.getCreatedAt() == null ? null : message.getCreatedAt().toString());
                    return row;
                })
                .toList();

        // 汇总返回：包含总消息数、实际返回数和消息列表。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", context.sessionId());
        result.put("totalMessages", all.size());
        result.put("returnedMessages", messages.size());
        result.put("messages", messages);
        return result;
    }

    /**
     * 读取并校验当前会话上下文。
     */
    private ToolExecutionContext requireContext() {
        // 上下文由工具执行链写入 ThreadLocal。
        ToolExecutionContext context = ToolExecutionContextHolder.get();
        if (context == null || context.sessionId() == null || context.sessionId().isBlank()) {
            // 没有 sessionId 时无法安全限定查询范围。
            throw new IllegalStateException("Session history requires session context");
        }
        return context;
    }
}
