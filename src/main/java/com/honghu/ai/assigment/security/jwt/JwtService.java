package com.honghu.ai.assigment.security.jwt;

import com.honghu.ai.assigment.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Objects;

/**
 * JWT 签发与校验服务（P0-1 的核心）。
 *
 * <p>替换用 HS256 签名 JWT：</p>
 * <ul>
 *     <li><b>无状态</b>：服务重启 / 多实例不丢登录态，天然适配水平扩展；</li>
 *     <li><b>不可伪造</b>：userId、role 写进 payload 并由服务端密钥签名，前端改一个字节就验签失败；</li>
 *     <li><b>可替换</b>：上层只通过 {@link JwtPrincipal} 取身份，将来换 RS256 / 外部 IdP 不影响业务层。</li>
 * </ul>
 *
 * <p>密钥与有效期来自配置 {@code app.security.jwt.*}；HS256 要求密钥至少 256 bit（32 字节），
 * 这里在启动时校验，避免上线后才发现弱密钥。</p>
 */
@Slf4j
@Service
public class JwtService {

    /** 签名密钥明文；生产务必通过环境变量 APP_SECURITY_JWT_SECRET 覆盖默认值。 */
    @Value("${app.security.jwt.secret:honghu-ai-dev-secret-change-me-please-0123456789-abcdefghijklmnop}")
    private String secret;

    /** token 有效期（小时）。 */
    @Value("${app.security.jwt.ttl-hours:8}")
    private long ttlHours;

    /** 签发者标识，便于多系统共用密钥时区分来源。 */
    @Value("${app.security.jwt.issuer:honghu-ai}")
    private String issuer;

    /** 由密钥派生的 HMAC-SHA Key，启动后缓存复用。 */
    private SecretKey signingKey;

    @PostConstruct
    void init() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "app.security.jwt.secret 至少需要 32 字节（256 bit）才能用于 HS256，当前长度=" + keyBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 为已认证用户签发 JWT。
     *
     * @param user 认证通过的用户（含游客）
     * @return token 元数据，直接回填到登录响应
     */
    public IssuedToken issue(User user) {
        Objects.requireNonNull(user, "user must not be null");
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofHours(Math.max(1, ttlHours)));
        String token = Jwts.builder()
                .issuer(issuer)
                .subject(user.getUserId())
                .claim("username", user.getUsername())
                .claim("role", user.getUserRole() == null ? User.UserRole.USER.name() : user.getUserRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
        LocalDateTime expiresAtLocal = LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault());
        return new IssuedToken(token, "Bearer", expiresAtLocal);
    }

    /**
     * 校验并解析 JWT。
     *
     * @param token 不含 “Bearer ” 前缀的原始 token
     * @return 可信主体；token 缺失返回 null
     * @throws JwtException 当 token 存在但签名无效 / 过期 / 结构非法时抛出，由调用方决定如何处理
     */
    public JwtPrincipal parse(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        User.UserRole role;
        try {
            role = User.UserRole.valueOf(String.valueOf(claims.get("role")));
        } catch (IllegalArgumentException | NullPointerException ex) {
            // token 里的角色不在当前枚举范围（例如旧 token、被删枚举），按最低权限处理。
            role = User.UserRole.GUEST;
        }
        return new JwtPrincipal(claims.getSubject(), String.valueOf(claims.get("username")), role);
    }

    /**
     * 从 {@code Authorization: Bearer xxx} 中提取 token 部分。
     *
     * @param authorizationHeader 原始 Authorization 头
     * @return token 字符串；不是 Bearer 方案时返回 null
     */
    public String extractBearerToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            return null;
        }
        String value = authorizationHeader.trim();
        if (!value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = value.substring(7).trim();
        return StringUtils.hasText(token) ? token : null;
    }

    /**
     * 登录接口返回给前端的 token 数据。
     *
     * @param token        JWT 字符串
     * @param tokenType    固定 {@code Bearer}
     * @param expiresAt    过期时间（服务器本地时区），前端可用于提示重新登录
     */
    public record IssuedToken(String token, String tokenType, LocalDateTime expiresAt) {
    }
}
