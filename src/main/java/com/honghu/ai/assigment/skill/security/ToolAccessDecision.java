package com.honghu.ai.assigment.skill.security;

import com.honghu.ai.assigment.skill.core.AuditingToolCallback;

/**
 * 一次模型触发工具调用的授权判断结果。
 *
 * <p>{@link ToolGuard} 不直接抛异常，而是返回这个很小的值对象。这样
 * {@link AuditingToolCallback} 可以用同一套字段记录“允许执行”和“拒绝执行”的调用，
 * 避免高风险工具在写审计日志之前就静默失败。</p>
 *
 * @param allowed true 表示允许进入真实工具；false 表示必须拦截
 * @param reason 拒绝原因；当 {@code allowed=false} 时会返回给模型并写入审计日志
 */
public record ToolAccessDecision(boolean allowed, String reason) {

    /**
     * 创建允许执行的决策。
     *
     * @return 表示允许进入真实工具的决策
     */
    public static ToolAccessDecision allow() {
        // 允许时不需要 reason，审计日志也不会写错误信息。
        return new ToolAccessDecision(true, null);
    }

    /**
     * 创建拒绝执行的决策。
     *
     * @param reason 可以安全展示给模型/用户的拒绝说明
     * @return 表示必须拦截工具调用的决策
     */
    public static ToolAccessDecision deny(String reason) {
        // 拒绝原因为空时给一个默认文案，保证返回给模型/日志的 error 不为空。
        return new ToolAccessDecision(false, reason == null || reason.isBlank()
                ? "Tool call denied"
                : reason);
    }
}
