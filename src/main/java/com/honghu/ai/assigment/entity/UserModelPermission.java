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

import java.time.LocalDateTime;

/**
 * 用户级模型权限覆盖。
 *
 * <p>角色默认模型和企业套餐模型只是“基础规则”。这张表用于对单个用户做额外调整：
 * 可以临时授予某个高阶模型，也可以因为风控原因禁止某个模型。</p>
 *
 * <p>最终优先级固定为：DENY > GRANT > 角色/套餐基础规则。
 * 也就是说，只要存在有效 DENY，即使角色或套餐允许该模型，用户最终也看不到。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_model_permission")
public class UserModelPermission {

    /** 主键 ID。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 被覆盖权限的用户 ID。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 模型编码，对应 ai_model_definition.model_code。 */
    @Column(name = "model_code", nullable = false, length = 100)
    private String modelCode;

    /** 当前覆盖规则是否启用。禁用后不会参与最终权限计算。 */
    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /**
     * 覆盖类型。
     *
     * <p>GRANT 表示额外授予；DENY 表示明确禁止。后端按字符串保存，
     * 是为了减少迁移成本，但业务上只建议使用 GRANT / DENY。</p>
     */
    @Builder.Default
    @Column(name = "override_type", nullable = false, length = 10)
    private String overrideType = "GRANT";

    /**
     * 覆盖生效的 workspace。
     *
     * <p>为空表示所有 workspace 都生效；不为空表示只在该 workspace 下生效。
     * 数据库唯一索引按 userId + coalesce(workspaceId, GLOBAL) + modelCode 去重。</p>
     */
    @Column(name = "workspace_id", length = 64)
    private String workspaceId;

    /** 后台备注，说明为什么授予或禁止。 */
    @Column(name = "reason", length = 200)
    private String reason;

    /** 过期时间。为空表示长期有效。 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** 创建时间。 */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
