package com.honghu.ai.assigment.security;

import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.repository.UserRepository;
import com.honghu.ai.assigment.security.jwt.JwtPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * 解析能力 / 聊天等接口的可信调用者。
 *
 * <p>P0-1 之后，可信身份的唯一权威来源是 Spring Security 上下文里的 {@link JwtPrincipal}
 * （由 {@code JwtAuthenticationFilter} 验签 JWT 后写入）。本类优先读它；只有在显式打开
 * {@code app.security.dev-header-fallback} 的本地联调场景，才回落到 {@code X-User-Id} 头。
 * 这样既彻底去掉了“前端填个 userId 就能越权”，又不影响 curl / 历史脚本的本地调试。</p>
 *
 * <p>每个方法都返回完整 {@link User} 而不仅是字符串：技能系统需要角色做授权，且每次回库取角色
 * 可以防止客户端伪造角色或使用过期角色数据。</p>
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    /** 仅作为本地开发兜底用途的请求头。 */
    public static final String USER_ID_HEADER = "X-User-Id";

    private final UserRepository userRepository;

    /**
     * 是否允许未携带 JWT 的 {@code X-User-Id} 直接作为身份。
     *
     * <p>默认 false：与 {@code JwtAuthenticationFilter} 保持一致，生产只信任签名 JWT。</p>
     */
    @Value("${app.security.dev-header-fallback:false}")
    private boolean devHeaderFallback;

    /**
     * 解析当前调用者，无身份时返回游客。
     */
    public User currentOrGuest() {
        User user = resolveFromCurrentRequest();
        return user == null ? guestUser() : user;
    }

    /**
     * 解析当前调用者，拒绝匿名访问。
     */
    public User requireUser() {
        User user = resolveFromCurrentRequest();
        if (user == null || "guest".equals(user.getUserId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in first.");
        }
        return user;
    }

    /**
     * 把可信调用者 id 绑回仍然携带 userId 的请求 DTO。
     *
     * @return 可信 userId，或 {@code guest}
     */
    public String currentUserIdOrGuest() {
        return currentOrGuest().getUserId();
    }

    /**
     * 读取当前 servlet 请求并解析身份。
     *
     * @return 可信用户；匿名时返回 null
     */
    public User resolveFromCurrentRequest() {
        // 1) 优先用 Spring Security 上下文里的 JWT 主体——这是不可伪造的权威身份。
        User fromSecurityContext = resolveFromSecurityContext();
        if (fromSecurityContext != null) {
            return fromSecurityContext;
        }
        // 2) 本地开发兜底：从请求头取 X-User-Id（仅 dev-header-fallback=true 时）。
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        return resolveFromHeader(attributes.getRequest());
    }

    /**
     * 解析一个 HTTP 请求的身份（保留旧签名，供直接持有 request 的调用方使用）。
     *
     * @param request 当前 servlet 请求
     * @return 可信用户或 null
     */
    public User resolve(HttpServletRequest request) {
        User fromSecurityContext = resolveFromSecurityContext();
        if (fromSecurityContext != null) {
            return fromSecurityContext;
        }
        return resolveFromHeader(request);
    }

    /**
     * 从 Spring Security 上下文解析 JWT 主体并回库取最新用户。
     */
    private User resolveFromSecurityContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (!(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
            return null;
        }
        if (principal.isGuest()) {
            return guestUser();
        }
        return userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login user no longer exists."));
    }

    /**
     * 本地兜底：从 {@code X-User-Id} 头解析身份。
     */
    private User resolveFromHeader(HttpServletRequest request) {
        if (request == null || !devHeaderFallback) {
            return null;
        }
        String headerUserId = request.getHeader(USER_ID_HEADER);
        if (!StringUtils.hasText(headerUserId)) {
            return null;
        }
        return userRepository.findById(headerUserId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Header user does not exist."));
    }

    private User guestUser() {
        return User.builder()
                .userId("guest")
                .username("guest")
                .nickname("guest")
                .userStatus(User.UserStatus.ACTIVE)
                .userRole(User.UserRole.GUEST)
                .build();
    }
}
