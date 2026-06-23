package com.honghu.ai.assigment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.config.properties.WeChatProperties;
import com.honghu.ai.assigment.dto.WeChatAuthorizeResponse;
import com.honghu.ai.assigment.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * 微信扫码登录服务(网站应用 / snsapi_login)。
 *
 * <p>完整实现微信开放平台「网站应用」OAuth2 授权码流程的服务端:</p>
 * <ol>
 *     <li>{@link #buildAuthorize()} 生成授权地址 + 一次性 {@code state}(写 Redis,5 分钟过期),前端据此扫码;</li>
 *     <li>用户扫码授权后微信回跳带回 {@code code}+{@code state};</li>
 *     <li>{@link #login(String, String)} 先校验并消费 {@code state}(防 CSRF / 重放),再用 {@code code}
 *         换取 {@code openid}(及 {@code unionid}),拉取昵称,最后 find-or-create 平台用户。</li>
 * </ol>
 *
 * <p>AppSecret 只存在于后端,绝不下发前端;无真实凭证时整体降级为演示(mock)模式,便于本地/面试演示。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeChatAuthService {

    /** 网站应用扫码授权页(前端跳转或内嵌 WxLogin 用)。 */
    private static final String QR_CONNECT_URL = "https://open.weixin.qq.com/connect/qrconnect";
    /** code 换 access_token / openid。 */
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    /** 拉取用户公开信息(昵称、头像)。 */
    private static final String USER_INFO_URL = "https://api.weixin.qq.com/sns/userinfo";
    /** 网站应用扫码固定作用域。 */
    private static final String SCOPE = "snsapi_login";

    /** Redis 中 state 的 key 前缀与有效期(一次性,短过期防 CSRF/重放)。 */
    private static final String STATE_KEY_PREFIX = "wechat:oauth:state:";
    private static final Duration STATE_TTL = Duration.ofMinutes(5);

    private final WeChatProperties properties;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 生成前端发起扫码所需的授权信息。
     *
     * @return 演示模式返回 mock 标记;真实模式返回 appId/redirectUri/state/url
     */
    public WeChatAuthorizeResponse buildAuthorize() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录未启用");
        }
        if (useMock()) {
            // 演示模式:返回一个占位 state,前端展示"演示登录"按钮即可。
            return new WeChatAuthorizeResponse(true, "", "", "demo-" + shortHash(UUID.randomUUID().toString()), SCOPE, "");
        }
        String redirectUri = properties.getRedirectUri();
        if (!StringUtils.hasText(redirectUri)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "微信登录已配置 appId 但缺少 redirect-uri,请设置 APP_AUTH_WECHAT_REDIRECT_URI");
        }
        String state = createState();
        String url = UriComponentsBuilder.fromHttpUrl(QR_CONNECT_URL)
                .queryParam("appid", properties.getAppId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .build().encode().toUriString() + "#wechat_redirect";
        return new WeChatAuthorizeResponse(false, properties.getAppId(), redirectUri, state, SCOPE, url);
    }

    /**
     * 用微信授权 code 完成登录。
     *
     * @param code  扫码回跳带回的一次性 code
     * @param state 回跳带回的 state,与发起时一致才放行(防 CSRF / 重放)
     * @return 命中或新建的平台用户
     */
    public User login(String code, String state) {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "微信登录未启用");
        }
        if (!StringUtils.hasText(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少微信授权 code");
        }
        validateState(state);
        WeChatIdentity identity = useMock() ? mockExchange(code) : realExchange(code);
        return userService.findOrCreateWeChatUser(identity.openid(), identity.unionid(), identity.nickname());
    }

    /** 是否演示模式:显式开启 mock,或缺少真实 appId/appSecret。 */
    private boolean useMock() {
        return properties.isMockEnabled()
                || !StringUtils.hasText(properties.getAppId())
                || !StringUtils.hasText(properties.getAppSecret());
    }

    /** 生成一次性 state 并写入 Redis(短过期)。 */
    private String createState() {
        String state = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(STATE_KEY_PREFIX + state, "1", STATE_TTL);
        return state;
    }

    /** 校验并消费 state:存在即删除(一次性);演示模式不强制。 */
    private void validateState(String state) {
        if (useMock()) {
            return;
        }
        if (!StringUtils.hasText(state)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 state");
        }
        Boolean removed = redisTemplate.delete(STATE_KEY_PREFIX + state);
        if (!Boolean.TRUE.equals(removed)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "state 无效或已过期,请重新发起微信登录");
        }
    }

    /** 演示模式:用 code 派生稳定 openid,不请求微信。 */
    private WeChatIdentity mockExchange(String code) {
        String openid = "mock-openid-" + shortHash(code);
        log.warn("微信登录运行在演示(mock)模式,openid 由 code 派生,勿用于生产:openid={}", openid);
        return new WeChatIdentity(openid, null, "微信用户");
    }

    /** 真实模式:code 换 openid/access_token,并尽量拉取昵称。 */
    private WeChatIdentity realExchange(String code) {
        String tokenUrl = UriComponentsBuilder.fromHttpUrl(ACCESS_TOKEN_URL)
                .queryParam("appid", properties.getAppId())
                .queryParam("secret", properties.getAppSecret())
                .queryParam("code", code)
                .queryParam("grant_type", "authorization_code")
                .toUriString();
        JsonNode token = getJson(tokenUrl, "换取 openid");
        if (token.hasNonNull("errcode") && token.get("errcode").asInt() != 0) {
            String errmsg = token.path("errmsg").asText("unknown");
            log.warn("微信换取 openid 失败:errcode={}, errmsg={}", token.get("errcode").asInt(), errmsg);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "微信登录失败:" + errmsg);
        }
        String openid = token.path("openid").asText(null);
        if (!StringUtils.hasText(openid)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "微信未返回 openid");
        }
        String unionid = token.path("unionid").asText(null);
        String accessToken = token.path("access_token").asText(null);
        String nickname = fetchNickname(accessToken, openid);
        return new WeChatIdentity(openid, unionid, nickname);
    }

    /** 尽力拉取微信昵称;失败不阻断登录,回退默认昵称。 */
    private String fetchNickname(String accessToken, String openid) {
        if (!StringUtils.hasText(accessToken)) {
            return null;
        }
        try {
            String url = UriComponentsBuilder.fromHttpUrl(USER_INFO_URL)
                    .queryParam("access_token", accessToken)
                    .queryParam("openid", openid)
                    .toUriString();
            JsonNode info = getJson(url, "拉取用户信息");
            if (info.hasNonNull("errcode") && info.get("errcode").asInt() != 0) {
                return null;
            }
            String nickname = info.path("nickname").asText(null);
            return StringUtils.hasText(nickname) ? nickname : null;
        } catch (RuntimeException ex) {
            log.warn("拉取微信昵称失败,使用默认昵称:{}", ex.getMessage());
            return null;
        }
    }

    /** 发起 GET 并解析 JSON;网络异常统一抛 502。 */
    private JsonNode getJson(String url, String action) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            return objectMapper.readTree(body);
        } catch (IOException ex) {
            log.error("调用微信端点异常({})", action, ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接微信服务器,请稍后再试");
        }
    }

    /** 取短哈希,保证同一个 code 在演示模式映射到同一 mock 用户。 */
    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /**
     * 微信身份解析结果。
     *
     * @param openid   微信 openid
     * @param unionid  微信 unionid(可空)
     * @param nickname 昵称(可空,演示模式给默认值)
     */
    private record WeChatIdentity(String openid, String unionid, String nickname) {
    }
}
