package com.honghu.ai.assigment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户注册请求 DTO。
 *
 * <p>相比直接 POST 一个 User 实体，这里用独立 DTO + Bean Validation 做入参校验：
 * 用户名/密码必填且有长度约束，手机号/邮箱格式可选校验，避免脏数据落库。</p>
 */
@Data
@Schema(description = "用户注册请求")
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度需在 3-50 之间")
    @Schema(description = "用户名（登录名）", example = "zhangsan")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6-64 之间")
    @Schema(description = "密码（明文传输，后端 BCrypt 落库）", example = "password123", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String password;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    @Schema(description = "手机号（可选）", example = "13800138000")
    private String phone;

    @Email(message = "邮箱格式不正确")
    @Schema(description = "邮箱（可选）", example = "zhangsan@example.com")
    private String email;

    @Size(max = 100, message = "昵称过长")
    @Schema(description = "昵称（可选，默认取用户名）", example = "张三")
    private String nickname;
}
