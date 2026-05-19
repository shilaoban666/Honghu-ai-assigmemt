package com.honghu.ut.test.ai.assigment.testdeepseekr1.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 套餐权益配置。
 *
 * <p>plan 只描述套餐本身，例如 free、team_pro、enterprise。
 * 真正决定套餐能用哪些模型、哪些 provider、额度多少的是这张表。</p>
 *
 * <p>为了让第一版后台简单可配置，这里把多类权益放在同一张表：
 * 模型白名单、provider 白名单、功能开关、额度限制都用 entitlementType 区分。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "plan_entitlement")
public class PlanEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 套餐编码，对应 plan.plan_code。 */
    @Column(name = "plan_code", nullable = false, length = 64)
    private String planCode;

    /** 权益类型，决定 entitlementKey 和 value 字段怎么解释。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "entitlement_type", nullable = false, length = 32)
    private EntitlementType entitlementType;

    /**
     * 权益 key。
     *
     * <p>示例：</p>
     * <ul>
     *     <li>ALLOWED_MODEL: gpt-5.4</li>
     *     <li>ALLOWED_PROVIDER: deepseek 或 *</li>
     *     <li>LIMIT: DAILY_BUDGET、MONTHLY_BUDGET、CONCURRENT_REQUESTS</li>
     *     <li>CAPABILITY: 预留的功能编码，例如 rag、tools</li>
     * </ul>
     */
    @Column(name = "entitlement_key", nullable = false, length = 100)
    private String entitlementKey;

    /** 文本值，主要给 CAPABILITY 或扩展权益使用。 */
    @Column(name = "value_text", length = 500)
    private String valueText;

    /** 数值，LIMIT 类型使用较多；金额类单位目前是 CNY。 */
    @Column(name = "value_number", precision = 18, scale = 4)
    private BigDecimal valueNumber;

    /** 是否启用该权益。删除权益时建议软删为 false，保留配置历史。 */
    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum EntitlementType {
        /** 精确开放一个模型。 */
        ALLOWED_MODEL,
        /** 开放一个 provider 下的模型，特殊 key "*" 表示全部 provider。 */
        ALLOWED_PROVIDER,
        /** 功能能力开关，当前预留。 */
        CAPABILITY,
        /** 额度或并发类限制。 */
        LIMIT
    }
}
