package com.honghu.ai.assigment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 模型价格快照。
 *
 * <p>价格不是直接写在模型定义表里，而是单独做快照表，原因是价格会变。
 * 旧调用必须绑定当时使用的价格，否则后续调价会污染历史账单。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ai_model_pricing")
public class AiModelPricing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 模型编码，对应 ai_model_definition.model_code。 */
    @Column(name = "model_code", nullable = false, length = 100)
    private String modelCode;

    /** 币种，目前默认 CNY，后续如果支持 USD 可以在这里扩展。 */
    @Builder.Default
    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "CNY";

    /** 输入 token 单价，单位是“每 100 万 token 多少钱”。 */
    @Column(name = "prompt_price_per_million", nullable = false, precision = 18, scale = 6)
    private BigDecimal promptPricePerMillion;

    /** 输出 token 单价，单位是“每 100 万 token 多少钱”。 */
    @Column(name = "completion_price_per_million", nullable = false, precision = 18, scale = 6)
    private BigDecimal completionPricePerMillion;

    /**
     * 缓存命中的输入 token 单价。
     *
     * <p>OpenAI、Anthropic 等 provider 可能对 cached input 给折扣。
     * 如果这里为空，计费逻辑会按普通输入价格计算，避免少扣费。</p>
     */
    @Column(name = "cached_input_price_per_million", precision = 18, scale = 6)
    private BigDecimal cachedInputPricePerMillion;

    /** 每次请求固定附加费，默认 0。可用于覆盖供应商按请求收费的模型。 */
    @Builder.Default
    @Column(name = "request_surcharge", nullable = false, precision = 18, scale = 6)
    private BigDecimal requestSurcharge = BigDecimal.ZERO;

    /** 平台加价倍率。1.0000 表示不加价，1.2000 表示在供应商成本基础上加 20%。 */
    @Builder.Default
    @Column(name = "markup_ratio", nullable = false, precision = 6, scale = 4)
    private BigDecimal markupRatio = BigDecimal.ONE;

    /** 价格开始生效时间。BillingService 会按调用时间命中当时的价格快照。 */
    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    /** 价格结束生效时间。为空表示当前仍然有效。 */
    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    /** 是否启用这条价格。禁用后不会被当前计费命中，但历史调用仍保留 pricingId。 */
    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
