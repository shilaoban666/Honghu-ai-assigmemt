package com.honghu.ai.assigment.entity;

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
 * 工作空间。
 *
 * <p>workspace 是 AI 调用的业务上下文。个人用户有个人 workspace；
 * 企业客户可以有企业 workspace。是否走企业套餐逻辑，主要看 planCode 是否为空。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "workspace")
public class Workspace {

    /** workspace ID。个人 workspace 当前直接使用 userId，企业 workspace 可以使用 UUID。 */
    @Id
    @Column(name = "workspace_id", length = 64)
    private String workspaceId;

    /** 所属组织 ID。 */
    @Column(name = "org_id", nullable = false, length = 64)
    private String orgId;

    /** workspace 展示名称。 */
    @Column(name = "name", nullable = false, length = 160)
    private String name;

    /**
     * 套餐编码。
     *
     * <p>为空表示个人空间，模型和配额按用户角色计算。
     * 非空表示企业空间，模型和配额按套餐权益计算。</p>
     */
    @Column(name = "plan_code", length = 64)
    private String planCode;

    /** workspace 状态，例如 ACTIVE。 */
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
