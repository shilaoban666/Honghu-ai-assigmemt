package com.honghu.ut.test.ai.assigment.testdeepseekr1.security;

import com.honghu.ut.test.ai.assigment.testdeepseekr1.entity.User;
import com.honghu.ut.test.ai.assigment.testdeepseekr1.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
 * Lightweight login-token service for normal front-end users.
 *
 * <p>The project currently does not include a full Spring Security/JWT stack.
 * P1 still needs a trusted identity source, so this service provides the
 * smallest production-shaped bridge: after a successful user login the backend
 * creates a random Bearer token, stores only its SHA-256 digest in memory, and
 * later resolves {@code Authorization: Bearer <token>} back to a database user.
 * Controllers can then ignore client-supplied {@code userId} values for
 * authorization-sensitive work.</p>
 *
 * <p>This is intentionally separate from {@code AdminAuthService}. Admin
 * sessions are restricted to ADMIN users and serve the back-office API, while
 * this service represents ordinary chat/capability users. Replacing this class
 * with JWT, gateway-issued principals, Redis sessions, or an external IdP later
 * will not require capability/runtime code to change because they depend only
 * on {@link CurrentUserService}.</p>
 */
@Service
@RequiredArgsConstructor
public class UserSessionTokenService {

    /** Secure random source used to create opaque bearer tokens. */
    private final SecureRandom secureRandom = new SecureRandom();

    /** User repository used to rehydrate a token session into the latest role/status. */
    private final UserRepository userRepository;

    /**
     * Token life in hours.
     *
     * <p>The default is intentionally short enough for a developer machine while
     * still allowing normal local testing. Production should back this with
     * Redis/JWT and revocation rules instead of relying on process memory.</p>
     */
    @Value("${app.security.user-token-ttl-hours:8}")
    private long tokenTtlHours;

    /**
     * In-memory session table keyed by token hash, never by the raw token.
     *
     * <p>Keeping only a hash means accidental logs or heap inspection do not
     * immediately reveal usable bearer credentials.</p>
     */
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

    /**
     * Creates a new bearer token for the supplied user.
     *
     * @param user authenticated user; may be the synthetic guest user
     * @return token metadata returned by the login API
     */
    public IssuedToken issueToken(User user) {
        Objects.requireNonNull(user, "user must not be null");
        String token = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now().plus(Duration.ofHours(Math.max(1, tokenTtlHours)));
        sessions.put(tokenHash(token), new UserSession(user.getUserId(), expiresAt));
        return new IssuedToken(token, "Bearer", expiresAt);
    }

    /**
     * Resolves a bearer token to a user.
     *
     * @param authorizationHeader raw Authorization header
     * @return resolved user, null when no bearer token was supplied
     * @throws ResponseStatusException when a token is present but invalid/expired
     */
    public User resolveUserByBearerToken(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token == null) {
            return null;
        }
        String hash = tokenHash(token);
        UserSession session = sessions.get(hash);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login token is invalid. Please sign in again.");
        }
        if (session.expiresAt().isBefore(LocalDateTime.now())) {
            sessions.remove(hash);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login token has expired. Please sign in again.");
        }
        if ("guest".equals(session.userId())) {
            return User.builder()
                    .userId("guest")
                    .username("guest")
                    .nickname("guest")
                    .userStatus(User.UserStatus.ACTIVE)
                    .userRole(User.UserRole.GUEST)
                    .build();
        }
        return userRepository.findById(session.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login user no longer exists."));
    }

    /**
     * Invalidates the current bearer token, if present.
     *
     * @param authorizationHeader raw Authorization header
     */
    public void logout(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        if (token != null) {
            sessions.remove(tokenHash(token));
        }
    }

    /**
     * Extracts the token part from {@code Authorization: Bearer ...}.
     *
     * @param authorizationHeader raw header value
     * @return token string or null when the header is absent/not bearer
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

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Objects.requireNonNull(token).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash bearer token", ex);
        }
    }

    /**
     * Immutable snapshot of one in-memory user session.
     *
     * @param userId trusted database user id
     * @param expiresAt absolute expiration time
     */
    private record UserSession(String userId, LocalDateTime expiresAt) {
    }

    /**
     * Token data returned to the front end after login.
     *
     * @param token opaque bearer token
     * @param tokenType fixed {@code Bearer} scheme
     * @param expiresAt absolute expiration time
     */
    public record IssuedToken(String token, String tokenType, LocalDateTime expiresAt) {
    }
}
