package com.honghu.ut.test.ai.assigment.testdeepseekr1.skill.core;

/**
 * 一次工具调用的可信服务端上下文。
 *
 * <p>模型只能控制工具参数，不能控制安全敏感上下文。当前用户、会话、触发消息 id、原始用户问题等信息
 * 由后端通过 Spring AI {@code ToolContext} 注入，再复制到这个 record 中，并通过
 * {@link ToolExecutionContextHolder} 暴露给内置工具。这样工具方法就不需要把 {@code userId}
 * 设计成模型可填写参数，从而避免模型伪造身份或会话。</p>
 *
 * @param userId 已认证调用者 id；匿名或系统调用时可为空
 * @param sessionId 本次工具调用所属聊天会话 id
 * @param messageId 触发本次工具调用的用户消息 id
 * @param query 本轮原始用户问题，可用于工具默认检索词
 */
public record ToolExecutionContext(
        String userId,
        String sessionId,
        Long messageId,
        String query
) {
}
