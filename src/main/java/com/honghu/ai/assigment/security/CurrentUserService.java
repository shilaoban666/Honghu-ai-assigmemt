package com.honghu.ai.assigment.security;

import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the trusted caller for capability and chat APIs.
 *
 * <p>P1 changes the security posture from "trust whatever {@code X-User-Id}
 * says" to "prefer server-issued Bearer tokens". During local development the
 * project can still enable a header fallback via {@code app.security.dev-header-fallback}
 * so older smoke tests and manual curl calls keep working. Production should
 * leave that fallback disabled and eventually replace this class with a normal
 * Spring Security principal lookup.</p>
 *
 * <p>Every method returns a full {@link User} object instead of just a string.
 * The skill system needs the role for authorization, and reloading it from the
 * database prevents clients from inventing roles or using stale role data.</p>
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    /** Header used only as a development fallback. */
    public static final String USER_ID_HEADER = "X-User-Id";

    /** Standard HTTP Authorization header. */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    private final UserSessionTokenService tokenService;
    private final UserRepository userRepository;

    /**
     * Allows the old {@code X-User-Id} shortcut.
     *
     * <p>The default is {@code true} so the existing developer workflow and
     * standalone MockMvc tests remain usable. Set {@code APP_SECURITY_DEV_HEADER_FALLBACK=false}
     * in production to enforce bearer-only identity.</p>
     */
    @Value("${app.security.dev-header-fallback:true}")
    private boolean devHeaderFallback;

    /**
     * Resolves the current caller and returns guest when no identity is present.
     *
     * @return authenticated user, dev-header user, or synthetic guest
     */
    public User currentOrGuest() {
        User user = resolveFromCurrentRequest();
        return user == null ? guestUser() : user;
    }

    /**
     * Resolves the current caller and rejects anonymous access.
     *
     * @return authenticated or dev-header user
     */
    public User requireUser() {
        User user = resolveFromCurrentRequest();
        if (user == null || "guest".equals(user.getUserId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in first.");
        }
        return user;
    }

    /**
     * Binds the trusted caller id into request DTOs that still carry userId.
     *
     * <p>This helper is intentionally tiny but centralizes the P1 rule: request
     * bodies may display who the client thinks it is, but authorization-sensitive
     * execution uses the trusted user resolved here.</p>
     *
     * @return trusted user id, or {@code guest}
     */
    public String currentUserIdOrGuest() {
        return currentOrGuest().getUserId();
    }

    /**
     * Reads the current servlet request and resolves identity.
     *
     * @return trusted user, or null when the request is anonymous
     */
    public User resolveFromCurrentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        return resolve(attributes.getRequest());
    }

    /**
     * Resolves identity from one HTTP request.
     *
     * @param request current servlet request
     * @return trusted user or null
     */
    public User resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        User bearerUser = tokenService.resolveUserByBearerToken(request.getHeader(AUTHORIZATION_HEADER));
        if (bearerUser != null) {
            return bearerUser;
        }
        if (devHeaderFallback) {
            String headerUserId = request.getHeader(USER_ID_HEADER);
            if (StringUtils.hasText(headerUserId)) {
                return userRepository.findById(headerUserId.trim())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Header user does not exist."));
            }
        }
        return null;
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
