package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户信息响应 DTO（不包含密码）。
 *
 * <p>该 DTO 既用于普通用户查询接口，也作为登录接口的统一返回对象。</p>
 * <p>因此除了基础用户字段外，还额外包含登录场景所需的：</p>
 * <ul>
 *     <li>identity：当前身份枚举</li>
 *     <li>identityLabel：身份中文名称</li>
 *     <li>permissionSummary：模型权限摘要</li>
 *     <li>availableModels：当前身份真正可用的模型列表</li>
 * </ul>
 *
 * @author shilaoban
 * @since 2026-03-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户信息响应（不含密码）")
public class UserResponse {

    @Schema(description = "用户 ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private String userId;

    @Schema(description = "用户名（登录名）", example = "zhangsan")
    private String username;

    @Schema(description = "昵称", example = "张三")
    private String nickname;

    @Schema(description = "手机号", example = "13800138000")
    private String phone;

    @Schema(description = "邮箱", example = "zhangsan@example.com")
    private String email;

    @Schema(description = "性别", example = "MALE")
    private User.Gender gender;

    @Schema(description = "用户状态", example = "ACTIVE")
    private User.UserStatus userStatus;

    @Schema(description = "用户角色", example = "USER")
    private User.UserRole userRole;

    @Schema(description = "登录后的身份枚举", example = "USER")
    private User.UserRole identity;

    @Schema(description = "身份中文名称", example = "普通用户")
    private String identityLabel;

    @Schema(description = "权限摘要", example = "普通用户只能使用第二梯队模型")
    private String permissionSummary;

    @Schema(description = "当前可用模型列表")
    private List<AiModelResponse> availableModels;

    @Schema(description = "家庭地址", example = "北京市朝阳区 XX 街道 XX 号")
    private String homeAddress;

    @Schema(description = "创建时间", example = "2026-03-15T10:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间", example = "2026-03-15T10:00:00")
    private LocalDateTime updatedAt;

    /**
     * 从 User 实体转换为 UserResponse
     *
     * @param user 用户实体
     * @return 用户响应 DTO
     */
    public static UserResponse fromUser(User user) {
        return UserResponse.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .phone(user.getPhone())
                .email(user.getEmail())
                .gender(user.getGender())
                .userStatus(user.getUserStatus())
                .userRole(user.getUserRole())
                .homeAddress(user.getHomeAddress())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    /**
     * 从 User 实体构造“登录响应版” UserResponse。
     *
     * <p>相比 {@link #fromUser(User)}，这个方法会额外填充身份说明和可用模型列表。</p>
     *
     * @param user 用户实体
     * @param identity 当前身份
     * @param identityLabel 身份中文名称
     * @param permissionSummary 权限摘要
     * @param availableModels 当前可用模型列表
     * @return 带登录权限信息的用户响应 DTO
     */
    public static UserResponse fromLoginUser(
            User user,
            User.UserRole identity,
            String identityLabel,
            String permissionSummary,
            List<AiModelResponse> availableModels) {
        UserResponse response = fromUser(user);
        response.setIdentity(identity);
        response.setIdentityLabel(identityLabel);
        response.setPermissionSummary(permissionSummary);
        response.setAvailableModels(availableModels);
        return response;
    }
}
