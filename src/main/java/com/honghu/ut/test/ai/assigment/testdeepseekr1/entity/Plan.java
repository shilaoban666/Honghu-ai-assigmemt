package com.honghu.ut.test.ai.assigment.testdeepseekr1.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 企业套餐定义。
 *
 * <p>Plan 只保存套餐基本信息，例如 free、team_pro、enterprise。
 * 套餐真正包含哪些模型、provider、额度限制，由 {@link PlanEntitlement} 保存。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "plan")
public class Plan {

    /** 套餐编码，作为外部稳定标识，例如 free、team_pro、enterprise。 */
    @Id
    @Column(name = "plan_code", length = 64)
    private String planCode;

    /** 后台和前端展示名称。 */
    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    /** 套餐层级，用于排序。数字越大通常代表套餐越高。 */
    @Column(name = "tier")
    private Integer tier;

    /** 套餐说明。 */
    @Column(name = "description", length = 500)
    private String description;

    /** 套餐是否启用。禁用后不建议继续分配给新 workspace。 */
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
