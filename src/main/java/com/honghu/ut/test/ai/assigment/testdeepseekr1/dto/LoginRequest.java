package com.honghu.ut.test.ai.assigment.testdeepseekr1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户登录请求 DTO
 *
 * @author shilaoban
 * @since 2026-03-15
 */
@Data
@Schema(description = "用户登录请求")
public class LoginRequest {

    @Schema(description = "是否以游客身份登录；为 true 时可不传用户名密码", example = "false")
    private Boolean guestLogin = false;

    @NotBlank(message = "用户名不能为空")
    @Schema(description = "用户名（登录名）", example = "zhangsan")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "密码", example = "password123", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String password;
}
