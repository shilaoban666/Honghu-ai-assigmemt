package com.honghu.ut.test.ai.assigment.testdeepseekr1.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 用户实体类
 *
 * @author shilaoban
 * @since 2026-03-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
@Schema(description= "用户信息")
public class User {

    /**
     * 用户 ID（UUID）
     */
    @Id
    @Column(name = "user_id", length = 64)
    @Schema(description = "用户 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private String userId;

    /**
     * 用户名（登录名）
     */
    @Column(name = "username", length = 50, nullable= false, unique = true)
    @Schema(description = "用户名（登录名）", example = "zhangsan")
    private String username;

    /**
     * 昵称
     */
    @Column(name = "nickname", length = 100)
    @Schema(description = "昵称", example= "张三")
    private String nickname;

    /**
     * 手机号
     */
    @Column(name = "phone", length = 20, unique = true)
    @Schema(description = "手机号", example = "13800138000")
    private String phone;

    /**
     * 邮箱
     */
    @Column(name = "email", length = 100, unique = true)
    @Schema(description = "邮箱", example = "zhangsan@example.com")
    private String email;

    /**
     * 密码（加密存储）
     */
    @Column(name = "password", length = 256, nullable = false)
    @Schema(description = "密码", example = "encoded_password_here", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String password;

    /**
     * 性别：MALE, FEMALE, OTHER
     */
    @Column(name = "gender", length = 10)
    @Schema(description= "性别", example = "MALE")
    @Enumerated(EnumType.STRING)
    private Gender gender;

    /**
     * 用户状态：ACTIVE, INACTIVE, BANNED
     */
    @Column(name = "user_status", length = 20)
    @Schema(description = "用户状态", example = "ACTIVE")
    @Enumerated(EnumType.STRING)
    private UserStatus userStatus;

    /**
     * 用户身份角色。
     *
     * <p>角色与模型能力的默认关系如下：</p>
     * <ul>
     *     <li>GUEST：游客，只能使用“普通模型”，默认限制为本地 + 第二梯队模型</li>
     *     <li>USER：普通用户，只能使用第二梯队模型</li>
     *     <li>VIP：VIP 用户，可使用全部梯队模型</li>
     *     <li>ADMIN：管理员，可使用全部梯队模型，并拥有全量管理权限</li>
     * </ul>
     */
    @Builder.Default
    @Column(name = "user_role", length = 20, nullable = false)
    @Schema(description = "用户角色", example = "USER")
    @Enumerated(EnumType.STRING)
    private UserRole userRole = UserRole.USER;

    /**
     * 家庭地址
     */
    @Column(name = "home_address", length = 500)
    @Schema(description= "家庭地址", example = "北京市朝阳区 XX 街道 XX 号")
    private String homeAddress;

    /**
     * 创建时间
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /**
     * 性别枚举
     */
    public enum Gender {
        @Schema(description = "男")
        MALE,
        
        @Schema(description = "女")
        FEMALE,
        
        @Schema(description = "其他")
        OTHER
    }

    /**
     * 用户状态枚举
     */
    public enum UserStatus {
        @Schema(description= "活跃")
        ACTIVE,

        @Schema(description = "未激活")
        INACTIVE,

        @Schema(description = "已封禁")
        BANNED,

        @Schema(description = "待验证")
        PENDING_VERIFICATION,

        @Schema(description = "待审核")
        PENDING_REVIEW,

        @Schema(description = "已注销")
        DELETED,
        @Schema(description = "已暂停")
        SUSPENDED,
        @Schema(description = "受限")
        RESTRICTED
    }


    /**
     * 用户角色枚举。
     */
    public enum UserRole {
        @Schema(description = "游客")
        GUEST,

        @Schema(description = "普通用户")
        USER,

        @Schema(description = "VIP 用户")
        VIP,

        @Schema(description = "管理员")
        ADMIN
    }
}
