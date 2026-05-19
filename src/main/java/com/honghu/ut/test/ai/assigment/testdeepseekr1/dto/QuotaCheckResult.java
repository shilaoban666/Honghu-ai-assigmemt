package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 配额检查结果。
 *
 * <p>这个对象是“调用前是否允许继续访问 AI”的统一返回值。
 * 它既要能告诉后端网关是否应该放行，也要能把足够多的上下文返回给前端，
 * 让前端把“超限原因、当前已用、上限多少、何时恢复”一次性展示出来。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaCheckResult {

    /** 是否允许继续发起 AI 调用。 */
    private boolean allowed;

    /**
     * 不允许调用时的原因码。
     *
     * <p>当前常见值：DAILY_LIMIT_EXCEEDED、MONTHLY_LIMIT_EXCEEDED。
     * 前端可以直接用它做错误文案和埋点分类。</p>
     */
    private String reason;

    /** 当日已消耗金额，单位和价格表币种一致，目前是 CNY。 */
    private BigDecimal dailyUsed;

    /** 当日上限；为空表示日额度不限。 */
    private BigDecimal dailyLimit;

    /** 当月已消耗金额，单位和价格表币种一致，目前是 CNY。 */
    private BigDecimal monthlyUsed;

    /** 当月上限；为空表示月额度不限。 */
    private BigDecimal monthlyLimit;

    /** 被拦截后建议前端提示的恢复时间，例如次日 00:00 或下月 1 日 00:00。 */
    private LocalDateTime resetAt;

    /**
     * 构造允许调用的结果。
     *
     * <p>allowed=true 时 reason 留空，其余字段仍然保留，方便前端展示“当前剩余额度”。</p>
     */
    public static QuotaCheckResult allowed(BigDecimal dailyUsed, BigDecimal dailyLimit,
                                           BigDecimal monthlyUsed, BigDecimal monthlyLimit,
                                           LocalDateTime resetAt) {
        return QuotaCheckResult.builder()
                .allowed(true)
                .dailyUsed(dailyUsed)
                .dailyLimit(dailyLimit)
                .monthlyUsed(monthlyUsed)
                .monthlyLimit(monthlyLimit)
                .resetAt(resetAt)
                .build();
    }

    /**
     * 构造被配额拦截的结果。
     *
     * <p>allowed=false 时 reason 会作为前后端统一错误码使用。</p>
     */
    public static QuotaCheckResult blocked(String reason, BigDecimal dailyUsed, BigDecimal dailyLimit,
                                           BigDecimal monthlyUsed, BigDecimal monthlyLimit,
                                           LocalDateTime resetAt) {
        return QuotaCheckResult.builder()
                .allowed(false)
                .reason(reason)
                .dailyUsed(dailyUsed)
                .dailyLimit(dailyLimit)
                .monthlyUsed(monthlyUsed)
                .monthlyLimit(monthlyLimit)
                .resetAt(resetAt)
                .build();
    }
}
