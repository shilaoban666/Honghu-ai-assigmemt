package com.honghu.ai.assigment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 微信扫码登录的授权信息（前端用它发起扫码）。
 *
 * <p>由后端生成并下发,前端据此渲染二维码或跳转授权页:</p>
 * <ul>
 *     <li>真实模式:返回 appId / redirectUri / state / url(qrconnect 授权地址)。
 *         {@code state} 由后端生成并写入 Redis(一次性、5 分钟过期),回调时校验,防 CSRF。</li>
 *     <li>演示模式:{@code mock=true},前端展示"演示登录"按钮,直接用占位 code 登录。</li>
 * </ul>
 *
 * @param mock        是否演示模式(无真实凭证时为 true)
 * @param appId       微信开放平台网站应用 AppID(公开,可暴露给前端)
 * @param redirectUri 授权回调地址(必须在微信后台配置为授权回调域)
 * @param state       一次性防伪随机串,回调需原样带回
 * @param scope       授权作用域,网站应用扫码固定 snsapi_login
 * @param url         拼好的 qrconnect 授权地址,前端可直接跳转
 */
@Schema(description = "微信扫码登录授权信息")
public record WeChatAuthorizeResponse(
        @Schema(description = "是否演示模式") boolean mock,
        @Schema(description = "网站应用 AppID") String appId,
        @Schema(description = "授权回调地址") String redirectUri,
        @Schema(description = "一次性防 CSRF state") String state,
        @Schema(description = "授权作用域") String scope,
        @Schema(description = "qrconnect 授权地址") String url) {
}
