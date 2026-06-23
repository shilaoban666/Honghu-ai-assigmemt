package com.honghu.ai.assigment.security.jwt;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>每个请求只跑一次：从 {@code Authorization: Bearer <jwt>} 解析可信主体，验签通过则写入
 * Spring Security 上下文，并用 {@link TrustedHeaderRequestWrapper} 把 {@code X-User-Id} 改写成
 * token 里的可信 userId，使大量直接读该头的旧 controller 自动获得可信身份。</p>
 *
 * <p>几个关键取舍：</p>
 * <ul>
 *     <li>token 无效（过期/篡改）时<strong>不直接 401</strong>，而是当作匿名继续，让授权规则统一裁决。
 *         这样管理端自带的 opaque token 经过本过滤器时不会被误杀（它不是 JWT，解析失败即视为无此 JWT）。</li>
 *     <li>没有有效 JWT 且 {@code app.security.dev-header-fallback=false}（生产默认）时，
 *         会抹掉伪造的 {@code X-User-Id}，从源头堵住越权。</li>
 *     <li>本地开发把 dev-header-fallback 打开，可继续用 X-User-Id 直连，方便 curl / 联调。</li>
 * </ul>
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    /**
     * 是否允许把未携带 JWT 的 {@code X-User-Id} 当作可信身份。
     *
     * <p>默认 false：生产环境只信任签名 JWT。本地联调可设
     * {@code APP_SECURITY_DEV_HEADER_FALLBACK=true} 临时放开。</p>
     */
    private final boolean devHeaderFallback;

    /**
     * 由 {@code SecurityConfig} 直接实例化（不暴露为 Bean），避免 Filter 作为 @Component
     * 被 Spring Boot 顶层 servlet 链重复注册。
     */
    public JwtAuthenticationFilter(JwtService jwtService, boolean devHeaderFallback) {
        this.jwtService = jwtService;
        this.devHeaderFallback = devHeaderFallback;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        JwtPrincipal principal = tryAuthenticate(request);

        HttpServletRequest effectiveRequest = request;
        if (principal != null) {
            // 验签成功：写入 SecurityContext + 用可信 userId 覆盖 X-User-Id。
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            effectiveRequest = new TrustedHeaderRequestWrapper(request, principal.userId());
        } else if (!devHeaderFallback) {
            // 没有有效 JWT 且关闭兜底：抹掉伪造的 X-User-Id，避免下游误信。
            effectiveRequest = new TrustedHeaderRequestWrapper(request, null);
        }

        try {
            filterChain.doFilter(effectiveRequest, response);
        } finally {
            // 无状态：每个请求结束清理上下文，避免线程复用导致身份串号。
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 尝试用 Authorization 头里的 JWT 认证。
     *
     * @return 可信主体；无 token 或 token 非法时返回 null（非法仅记日志，不抛出）
     */
    private JwtPrincipal tryAuthenticate(HttpServletRequest request) {
        String token = jwtService.extractBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            return null;
        }
        try {
            return jwtService.parse(token);
        } catch (JwtException ex) {
            // 可能是过期 / 被篡改的 JWT，也可能是管理端 opaque token 之类的非 JWT 串。
            // 统一按“未认证”处理，交给后续授权规则决定 401/403 或放行。
            log.debug("Bearer token 未通过 JWT 校验，按匿名处理：{}", ex.getMessage());
            return null;
        }
    }
}
