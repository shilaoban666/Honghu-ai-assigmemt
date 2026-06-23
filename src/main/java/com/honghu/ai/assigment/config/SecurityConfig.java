package com.honghu.ai.assigment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honghu.ai.assigment.security.jwt.JwtAuthenticationFilter;
import com.honghu.ai.assigment.security.jwt.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

/**
 * Spring Security 配置（P0-1）。
 *
 * <p>设计目标：在<strong>不破坏现有行为</strong>的前提下引入框架级认证授权。</p>
 * <ul>
 *     <li>无状态 + 关闭 CSRF：纯 REST + JWT，不依赖服务端 session。</li>
 *     <li>{@link JwtAuthenticationFilter} 负责把签名 JWT 还原成可信主体。</li>
 *     <li>登录 / 注册 / 健康检查 / Swagger / Prometheus 放行；用户列表等管理读接口要求 ADMIN。</li>
 *     <li>其余接口维持放行，保留“游客可体验”的产品形态——真正的逐用户鉴权仍由
 *         业务层（CurrentUserService / RagAccessGuard / AdminAuthService）兜底，
 *         这次只是把“可信身份从哪来”从请求头换成了不可伪造的 JWT。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    /** 是否允许 X-User-Id 兜底，透传给 JWT 过滤器（默认 false，仅本地联调放开）。 */
    @Value("${app.security.dev-header-fallback:false}")
    private boolean devHeaderFallback;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ---- 完全公开：认证入口与基础设施端点 ----
                        .requestMatchers(
                                "/api/v1/users/login",
                                "/api/v1/users/login/**",
                                "/api/v1/users/register",
                                "/api/v1/users/send-code",
                                "/api/v1/users/check/**",
                                "/api/v1/users/wechat/**",
                                "/api/v1/admin/login"
                        ).permitAll()
                        // 兼容旧注册：POST /api/v1/users 直接创建用户
                        .requestMatchers(HttpMethod.POST, "/api/v1/users").permitAll()
                        // 监控 / 文档 / 静态资源
                        .requestMatchers(
                                "/actuator/health/**", "/actuator/health", "/actuator/info", "/actuator/prometheus",
                                "/swagger-ui/**", "/swagger-ui.html", "/api-docs/**", "/v3/api-docs/**",
                                "/favicon.ico", "/static/**", "/error"
                        ).permitAll()
                        // ---- 方法级 RBAC 示例：列出全部用户 / 按状态查用户，仅 ADMIN ----
                        .requestMatchers(HttpMethod.GET, "/api/v1/users").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/status/**").hasRole("ADMIN")
                        // ---- 后台接口：框架层放行，由 AdminAuthService 用独立会话校验 ADMIN ----
                        .requestMatchers("/api/v1/admin/**").permitAll()
                        // ---- 其余：维持放行，业务层按可信身份兜底鉴权 ----
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "没有访问权限")))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, devHeaderFallback),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 统一的密码编码器。
     *
     * <p>BCrypt 自带盐、可调成本因子；注册时编码、登录时 matches，历史明文密码在登录时自动升级（见 UserService）。</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS 配置源。
     *
     * <p>加了 Spring Security 后，跨域必须经过安全过滤链放行，因此在这里复刻原 WebConfig 的 CORS 策略，
     * 让浏览器预检 (OPTIONS) 能通过，且允许携带 Authorization 头。</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization", "X-Trace-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * 以 JSON 形式返回鉴权错误，保持和业务接口一致的响应风格。
     */
    private void writeError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "success", false,
                "status", status,
                "message", message));
    }
}
