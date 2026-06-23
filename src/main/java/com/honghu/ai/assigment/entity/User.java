package com.honghu.ai.assigment.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
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
     * 密码（BCrypt 加密存储）。
     *
     * <p>{@link JsonProperty.Access#WRITE_ONLY}：允许从请求体反序列化写入（注册/创建用户时需要），
     * 但<strong>永不</strong>序列化进任何 JSON 响应——防止 createUser / getAllUsers 等返回 User 实体的接口
     * 把密码哈希泄漏出去。（{@code @Schema(WRITE_ONLY)} 只影响 Swagger 文档，挡不住 Jackson 序列化。）</p>
     */
    @Column(name = "password", length = 256, nullable = false)
    @Schema(description = "密码", example = "encoded_password_here", accessMode = Schema.AccessMode.WRITE_ONLY)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
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
     * 用户头像在 S3 中的对象 key。
     */
    @Column(name = "avatar_object_key", length = 512)
    @Schema(description = "头像 S3 对象 Key", accessMode = Schema.AccessMode.READ_ONLY)
    private String avatarObjectKey;

    /**
     * 用户头像 MIME 类型。
     */
    @Column(name = "avatar_content_type", length = 100)
    @Schema(description = "头像 MIME 类型", example = "image/png", accessMode = Schema.AccessMode.READ_ONLY)
    private String avatarContentType;

    /**
     * 微信 openid（同一公众号/开放平台应用下唯一）。
     *
     * <p>微信扫码登录时，后端用授权 code 换取 openid，并以此 find-or-create 用户。
     * 该字段唯一，保证同一个微信用户重复登录命中的是同一条账户记录。</p>
     */
    @Column(name = "wechat_openid", length = 128, unique = true)
    @Schema(description = "微信 openid", accessMode = Schema.AccessMode.READ_ONLY)
    private String wechatOpenid;

    /**
     * 微信 unionid（同一开放平台主体下跨应用唯一，可能为空）。
     *
     * <p>仅当应用绑定了微信开放平台时才会返回；用于将来打通公众号 / 小程序 / 网站多端身份。</p>
     */
    @Column(name = "wechat_unionid", length = 128)
    @Schema(description = "微信 unionid", accessMode = Schema.AccessMode.READ_ONLY)
    private String wechatUnionid;

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
     * 平台角色说明。
     *
     * <p>这里的角色表示平台订阅档位和后台身份，不表示 workspace 内的团队角色。
     * workspace 内的 OWNER、ADMIN、MEMBER、VIEWER 保存在 workspace_member.member_role。</p>
     *
     * <p>个人空间下，模型默认授权和个人额度都由这个角色决定；
     * 企业 workspace 下，如果 workspace 配了 planCode，则模型和额度优先由套餐决定。</p>
     */
    public enum UserRole {
        @Schema(description = "游客")
        GUEST, // 游客：通常只开放最低试用额度和基础模型，用于未登录或体验态场景。

        @Schema(description = "普通用户")
        USER, // 普通用户：免费档个人用户，个人空间模型和额度按基础角色规则计算。

        @Schema(description = "订阅级 1")
        PRO, // PRO：个人订阅一级，通常拥有更高个人预算和更多可用模型。

        @Schema(description = "订阅级 2")
        PLUS, // PLUS：个人订阅二级，预算和模型范围进一步提升。

        @Schema(description = "订阅级 3")
        PRO_PLUS, // PRO_PLUS：个人订阅里的最高档，个人侧默认权限最宽。

        @Schema(description = "VIP 用户")
        VIP, // VIP：业务上的高权限用户，可不受个人预算约束，但不等同于后台管理员。

        @Schema(description = "管理员")
        ADMIN // ADMIN：后台管理员，除模型使用外，还拥有后台管理与运营配置权限。
    }
}
