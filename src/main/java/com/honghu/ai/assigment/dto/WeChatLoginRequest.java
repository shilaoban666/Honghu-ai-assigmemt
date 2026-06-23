package com.honghu.ai.assigment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 微信登录请求 DTO。
 *
 * <p>前端走微信开放平台「网站应用扫码登录」：用户扫码授权后，微信回跳并带回一次性 {@code code}，
 * 前端把它 POST 给后端换取 openid 并完成登录。{@code state} 用于前端自校验防 CSRF（后端透传即可）。</p>
 */
@Data
@Schema(description = "微信扫码登录请求")
public class WeChatLoginRequest {

    @NotBlank(message = "微信授权 code 不能为空")
    @Schema(description = "微信授权 code（扫码回跳带回，一次性）", example = "081abc...")
    private String code;

    @Schema(description = "防 CSRF 的 state，原样回传", example = "honghu-xyz")
    private String state;
}
