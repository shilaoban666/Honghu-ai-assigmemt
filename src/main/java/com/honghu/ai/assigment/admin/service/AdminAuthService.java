package com.honghu.ai.assigment.admin.service;

import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.repository.UserRepository;
import com.honghu.ai.assigment.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台管理员登录与 token 校验服务。
 *
 * <p>项目当前还没有完整 Spring Security/JWT 栈，所以这里先实现一个轻量级
 * Bearer token 登录态：管理员用用户名和密码登录后，后端生成随机 token 并保存在内存中。
 * 后续如果接入正式 JWT，只需要替换这个服务和 {@code AdminController.requireAdmin(...)}
 * 的调用方式，后台业务接口本身不用大改。</p>
 *
 * <p>为了兼容你之前已经联调过的接口，本服务仍保留 {@code X-User-Id} 兜底校验。
 * 新前端应优先使用 {@code Authorization: Bearer <token>}。</p>
 */
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    /**
     * 后台登录态的有效期。
     *
     * <p>当前实现把 session 放在内存里，因此 TTL 主要用于：
     * 避免 token 长期有效、降低泄漏风险、同时简化“重新登录续期”的心智模型。</p>
     */
    private static final Duration TOKEN_TTL = Duration.ofHours(8);

    /** 用户基础查询仓库，用于把 token/fallback userId 解析回真实管理员用户。 */
    private final UserRepository userRepository;
    /** 复用现有用户名密码认证逻辑，避免后台单独维护第二套密码体系。 */
    private final UserService userService;
    /** 安全随机数生成器，用于产生不可预测的登录 token。 */
    private final SecureRandom secureRandom = new SecureRandom();
    /**
     * 内存会话表。
     *
     * <p>key 存的不是明文 token，而是 token 的 SHA-256 摘要。
     * 这样即使调试时误打印 sessions，也不会直接泄漏真实 Bearer token。</p>
     */
    private final Map<String, AdminSession> sessions = new ConcurrentHashMap<>();

    /**
     * 使用普通用户账号密码登录后台。
     *
     * <p>只有 {@link User.UserRole#ADMIN} 可以登录后台。密码校验复用现有
     * {@link UserService#authenticate(String, String)}，这样不会引入第二套密码规则。</p>
     */
    public AdminLoginResult login(String username, String password) {
        User user = userService.authenticate(username, password);
        if (user.getUserRole() != User.UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号不是管理员，不能登录后台");
        }
        String token = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now().plus(TOKEN_TTL);
        sessions.put(tokenHash(token), new AdminSession(user.getUserId(), expiresAt));
        return new AdminLoginResult(token, "Bearer", expiresAt, user);
    }

    /**
     * 根据请求头解析管理员身份。
     *
     * <p>优先解析 Bearer token；如果没有 token，再兼容旧的 {@code X-User-Id}。
     * 这样前端可以先切到登录模式，老的接口调试脚本也还能继续用。</p>
     */
    public User requireAdmin(String authorizationHeader, String fallbackUserId) {
        User admin = resolveByBearerToken(authorizationHeader);
        if (admin == null && StringUtils.hasText(fallbackUserId)) {
            admin = userRepository.findById(fallbackUserId.trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理员用户不存在"));
        }
        if (admin == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录后台");
        }
        if (admin.getUserRole() != User.UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
        }
        return admin;
    }

    /**
     * 主动退出登录。
     *
     * <p>内存 token 删除后，前端继续带旧 token 会得到 401。</p>
     */
    public void logout(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token != null) {
            sessions.remove(tokenHash(token));
        }
    }

    /**
     * 从 Bearer token 解析管理员用户。
     *
     * <p>这里会同时做三件事：
     * 1. 解析 Authorization 头；
     * 2. 检查 token 是否存在、是否过期；
     * 3. 再回库确认管理员账号仍然存在。</p>
     */
    private User resolveByBearerToken(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token == null) {
            return null;
        }
        String hash = tokenHash(token);
        AdminSession session = sessions.get(hash);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "后台登录已失效，请重新登录");
        }
        if (session.expiresAt().isBefore(LocalDateTime.now())) {
            sessions.remove(hash);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "后台登录已过期，请重新登录");
        }
        return userRepository.findById(session.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "管理员用户不存在"));
    }

    /**
     * 从 Authorization 头里提取 Bearer token。
     *
     * <p>只接受 {@code Bearer xxx} 这种格式；
     * 如果前端传的是空串、其他 scheme 或只写了 Bearer 不带值，都按未登录处理。</p>
     */
    private String extractBearerToken(String authorizationHeader) {
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
     * 生成随机 token。
     *
     * <p>使用 32 字节随机数再做 URL-safe Base64 编码，
     * 既足够随机，也方便前端直接放进 HTTP Header。</p>
     */
    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 计算 token 摘要。
     *
     * <p>后台只把摘要放进内存 session 表，目的是“即便内存对象被观察到，也尽量不暴露原 token”。</p>
     */
    private String tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Objects.requireNonNull(token).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成 token 摘要", ex);
        }
    }

    /**
     * 后台登录会话快照。
     *
     * @param userId 登录管理员 ID
     * @param expiresAt 该会话的过期时间
     */
    private record AdminSession(String userId, LocalDateTime expiresAt) {
    }

    /**
     * 登录成功返回给前端的数据。
     *
     * @param token Bearer token，前端后续请求放入 Authorization 头
     * @param tokenType 固定为 Bearer，便于前端拼请求头
     * @param expiresAt 过期时间，前端可用于提示重新登录
     * @param user 管理员用户信息
     */
    public record AdminLoginResult(String token, String tokenType, LocalDateTime expiresAt, User user) {
    }
}
