package com.honghu.ut.test.ai.assigment.testdeepseekr1.entity;

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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户配额增量覆盖。
 *
 * <p>这张表不是保存“最终额度”，而是保存对基础额度的增量。
 * 基础额度来自角色或企业套餐，最终额度 = 基础额度 + 所有未过期 delta。</p>
 *
 * <p>典型场景：</p>
 * <ul>
 *     <li>客服给某用户本月补偿 50 元：monthlyDelta = 50。</li>
 *     <li>临时给某用户今天多 5 元：dailyDelta = 5，并设置 expiresAt。</li>
 * </ul>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_quota_override")
public class UserQuotaOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 被调整额度的用户。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 为空表示全局/个人上下文生效；不为空表示只对指定 workspace 生效。 */
    @Column(name = "workspace_id", length = 64)
    private String workspaceId;

    /** 日额度增量。可以为正数增加额度，也可以为负数减少额度。 */
    @Builder.Default
    @Column(name = "daily_delta", nullable = false, precision = 18, scale = 4)
    private BigDecimal dailyDelta = BigDecimal.ZERO;

    /** 月额度增量。可以为正数增加额度，也可以为负数减少额度。 */
    @Builder.Default
    @Column(name = "monthly_delta", nullable = false, precision = 18, scale = 4)
    private BigDecimal monthlyDelta = BigDecimal.ZERO;

    /** 后台备注，说明为什么加减额度。 */
    @Column(name = "reason", length = 200)
    private String reason;

    /** 过期时间。为空表示长期有效。 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
