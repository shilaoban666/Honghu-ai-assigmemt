package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core;

/**
 * 单次工具调用上下文。
 *
 * <p>模型调用工具时只会给 arguments，业务系统还需要知道是谁、在哪个会话、由哪条消息触发。
 * 这些信息不放进模型参数里，而是通过 Spring AI 的 {@code ToolContext} 和本项目的
 * {@link ToolExecutionContextHolder} 附带传递，避免模型伪造用户身份。</p>
 *
 * @param userId 当前用户 ID，匿名或内部调用可以为空
 * @param sessionId 当前会话 ID，用于会话级工具开关和日志归档
 * @param messageId 触发工具调用的消息 ID，没有消息上下文时为空
 * @param query 用户原始问题，工具需要理解查询意图时可读取
 */
public record ToolExecutionContext(
        // 当前用户 id；匿名或内部调用可以为空。
        String userId,
        // 当前会话 id；用于会话级工具开关和日志归档。
        String sessionId,
        // 触发工具调用的消息 id；没有消息上下文时为空。
        Long messageId,
        // 用户原始问题；工具需要理解查询意图时可读取。
        String query
) {
}
