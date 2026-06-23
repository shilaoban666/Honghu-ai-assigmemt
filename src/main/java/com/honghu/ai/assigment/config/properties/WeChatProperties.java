package com.honghu.ai.assigment.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 微信登录配置（app.auth.wechat）。
 *
 * <p>面向微信开放平台「网站应用扫码登录」。和项目里 S3/SQS 的风格一致：默认不强依赖真实凭证，
 * 没配 appId/appSecret 或显式开启 mock 时走演示模式，保证 clone 下来就能跑通登录链路；
 * 生产配上真实 appId/appSecret 并把 {@code mock-enabled} 置 false，即切换到真实微信换取 openid。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.auth.wechat")
public class WeChatProperties {

    /** 是否对外开放微信登录入口。 */
    private boolean enabled = true;

    /**
     * 是否使用演示（mock）模式。
     *
     * <p>true（或缺少真实凭证）时：用授权 code 派生一个稳定的 mock openid 完成 find-or-create，
     * 不真正请求微信服务器，便于本地与面试演示。生产务必置 false。</p>
     */
    private boolean mockEnabled = true;

    /** 微信开放平台网站应用 AppID。 */
    private String appId = "";

    /** 微信开放平台网站应用 AppSecret。 */
    private String appSecret = "";

    /** 扫码授权回跳地址（前端使用，后端透传用于排查），可选。 */
    private String redirectUri = "";
}
