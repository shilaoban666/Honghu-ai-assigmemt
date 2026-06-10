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
 * workspace 成员关系。
 *
 * <p>这个角色是“空间内角色”，只控制团队内权限，例如 OWNER、ADMIN、MEMBER、VIEWER。
 * 它和 {@link User.UserRole} 不是一回事：UserRole 管平台订阅档位和后台权限，
 * memberRole 管某个 workspace 内的团队权限。</p>
 */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "workspace_member")
public class WorkspaceMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** workspace ID。 */
    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    /** 成员用户 ID。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 空间内角色，推荐值 OWNER、ADMIN、MEMBER、VIEWER。 */
    @Column(name = "member_role", nullable = false, length = 20)
    private String memberRole;

    /** 成员状态，例如 ACTIVE。 */
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
