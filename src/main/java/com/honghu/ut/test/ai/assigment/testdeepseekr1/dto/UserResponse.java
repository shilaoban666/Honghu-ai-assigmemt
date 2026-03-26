package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户信息响应 DTO（不包含密码）
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
                .homeAddress(user.getHomeAddress())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
