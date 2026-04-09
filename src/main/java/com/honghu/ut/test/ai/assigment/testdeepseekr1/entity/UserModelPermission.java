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
 * 用户可用模型权限实体。
 *
 * <p>普通用户通过这张表控制“可选模型集合”；ADMIN 用户则默认拥有所有模型权限，
 * 不依赖该表逐条配置。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_model_permission")
public class UserModelPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 用户 ID。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 模型编码，对应 ai_model_definition.model_code。 */
    @Column(name = "model_code", nullable = false, length = 100)
    private String modelCode;

    /** 当前授权是否有效。 */
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

