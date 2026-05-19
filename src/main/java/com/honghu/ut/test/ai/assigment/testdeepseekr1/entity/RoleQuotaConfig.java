package com.honghu.ut.test.ai.assigment.testdeepseekr1.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 平台角色的个人额度配置。
 *
 * <p>当用户在个人 workspace 下调用模型时，额度来自这张表。
 * 企业 workspace 不走这张表，而是走 plan_entitlement 中的 LIMIT。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "role_quota_config")
public class RoleQuotaConfig {

    /** 角色名称，例如 USER、PRO、PLUS、PRO_PLUS、VIP、ADMIN。 */
    @Id
    @Column(name = "role", length = 20)
    private String role;

    /** 日额度，单位 CNY；为空表示日额度不限。 */
    @Column(name = "daily_limit", precision = 18, scale = 4)
    private BigDecimal dailyLimit;

    /** 月额度，单位 CNY；为空表示月额度不限。 */
    @Column(name = "monthly_limit", precision = 18, scale = 4)
    private BigDecimal monthlyLimit;

    /** 并发请求数上限。当前建表预留，v1 暂未强制执行。 */
    @Column(name = "concurrent_requests")
    private Integer concurrentRequests;

    /** 后台展示说明，例如“免费档”“9.9 元档”。 */
    @Column(name = "description", length = 200)
    private String description;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
