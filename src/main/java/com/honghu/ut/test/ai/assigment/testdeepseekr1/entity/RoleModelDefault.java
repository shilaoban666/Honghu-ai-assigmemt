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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 角色默认可用模型。
 *
 * <p>这张表替代了旧代码里按角色 switch 的硬编码。
 * 后台可以直接调整某个角色默认开放哪些模型，改完立即影响后续查询。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "role_model_default")
public class RoleModelDefault {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 平台角色，例如 USER、PRO、PLUS。 */
    @Column(name = "role", nullable = false, length = 20)
    private String role;

    /** 模型编码，对应 ai_model_definition.model_code。 */
    @Column(name = "model_code", nullable = false, length = 100)
    private String modelCode;

    /** 是否启用该角色-模型映射。取消授权时优先置 false，保留历史配置。 */
    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = Boolean.TRUE;

    /** 配置来源，例如 migration、admin，方便排查这条授权从哪里来。 */
    @Column(name = "source", length = 64)
    private String source;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
