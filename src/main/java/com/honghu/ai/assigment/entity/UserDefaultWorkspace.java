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
 * 用户默认 workspace。
 *
 * <p>用户没有显式传 X-Workspace-Id 时，后端会用这张表决定当前上下文。
 * 个人用户默认指向自己的个人 workspace；企业用户也可以把默认空间切到常用团队空间。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_default_workspace")
public class UserDefaultWorkspace {

    /** 用户 ID，一名用户只有一个默认 workspace。 */
    @Id
    @Column(name = "user_id", length = 64)
    private String userId;

    /** 默认 workspace ID。 */
    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
