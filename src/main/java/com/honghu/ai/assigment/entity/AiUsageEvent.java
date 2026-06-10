package com.honghu.ai.assigment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 调用流水。
 *
 * <p>这张表是成本、配额、监控、对账的“真相源”。它只追加，不用于覆盖更新。
 * 每一次真实进入 AI 网关的请求，无论成功、失败、被配额拦截还是缺少价格，都应该尽量写一条记录。</p>
 *
 * <p>注意：只有 {@link Status#SUCCESS} 的 cost_billed 会进入额度消耗。
 * 失败和拦截记录主要用于审计和运营分析。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ai_usage_event")
public class AiUsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 一次业务请求的唯一 ID，用于跨日志、流水和重试排查问题。 */
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    /** 同一个 requestId 下第几次尝试，默认 1；和 requestId 组成幂等键。 */
    @Builder.Default
    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo = 1;

    /** 调用用户 ID。匿名或系统调用可以为空。 */
    @Column(name = "user_id", length = 64)
    private String userId;

    /** 调用所在 workspace。企业账单和企业配额会按这个字段聚合。 */
    @Column(name = "workspace_id", length = 64)
    private String workspaceId;

    /** 聊天会话 ID，便于从用量流水追溯到会话。 */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** 聊天消息 ID，便于从用量流水追溯到具体消息。 */
    @Column(name = "chat_id")
    private Long chatId;

    /** 用户请求或路由最初选择的模型编码。 */
    @Column(name = "model_code", nullable = false, length = 100)
    private String modelCode;

    /** 实际执行的模型编码。本地模型不可用自动回退时，它可能和 modelCode 不同。 */
    @Column(name = "effective_model_code", length = 100)
    private String effectiveModelCode;

    /** 实际执行模型所属 provider，用于按供应商聚合用量。 */
    @Column(name = "provider_code", length = 64)
    private String providerCode;

    /** 输入 token 数。 */
    @Builder.Default
    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens = 0;

    /** 输出 token 数。 */
    @Builder.Default
    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens = 0;

    /** 命中 provider prompt cache 的输入 token 数。 */
    @Builder.Default
    @Column(name = "cached_prompt_tokens", nullable = false)
    private Integer cachedPromptTokens = 0;

    /** 总 token 数，通常等于 prompt + completion。 */
    @Builder.Default
    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens = 0;

    /** 本次调用命中的价格快照 ID，失败或缺价格时为空。 */
    @Column(name = "pricing_id")
    private Long pricingId;

    /** 按供应商原始价格计算出的成本。 */
    @Builder.Default
    @Column(name = "cost_vendor", nullable = false, precision = 20, scale = 8)
    private BigDecimal costVendor = BigDecimal.ZERO;

    /** 平台最终扣减用户/团队额度的金额。配额 SUM 使用这个字段。 */
    @Builder.Default
    @Column(name = "cost_billed", nullable = false, precision = 20, scale = 8)
    private BigDecimal costBilled = BigDecimal.ZERO;

    /** 币种，目前默认 CNY。 */
    @Builder.Default
    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "CNY";

    /** 网关侧观察到的调用耗时，单位毫秒。 */
    @Column(name = "latency_ms")
    private Long latencyMs;

    /** 调用状态，区分成功、失败、配额拦截和缺价格拦截。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    /** 失败或拦截时的错误摘要，最长 1000 字符。 */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 原始 token usage JSON，便于后续兼容更多 provider 的 usage 字段。 */
    @Column(name = "raw_usage_json", columnDefinition = "TEXT")
    private String rawUsageJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum Status {
        /** 调用成功，费用进入配额消耗。 */
        SUCCESS,
        /** 上游或本地调用失败，费用为 0。 */
        FAILED,
        /** 调用超时，费用为 0；当前预留状态。 */
        TIMEOUT,
        /** 调用前被配额拦截，费用为 0。 */
        BLOCKED_BY_QUOTA,
        /** 模型没有有效价格配置，费用为 0。 */
        BLOCKED_BY_PRICING
    }
}
