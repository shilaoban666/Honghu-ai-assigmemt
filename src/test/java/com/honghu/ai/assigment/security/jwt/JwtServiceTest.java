package com.honghu.ai.assigment.security.jwt;

import com.honghu.ai.assigment.entity.User;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtService} 单元测试：验证签发→解析往返、角色解析与防篡改。
 *
 * <p>纯单元测试，不加载 Spring 上下文，CI 无需基础设施即可运行。</p>
 */
class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        // HS256 要求密钥 >= 32 字节，这里给一段足够长的测试密钥。
        ReflectionTestUtils.setField(jwtService, "secret", "test-secret-test-secret-test-secret-0123456789");
        ReflectionTestUtils.setField(jwtService, "ttlHours", 8L);
        ReflectionTestUtils.setField(jwtService, "issuer", "honghu-ai-test");
        ReflectionTestUtils.invokeMethod(jwtService, "init");
    }

    @Test
    void issueThenParseShouldRoundTripIdentity() {
        User user = User.builder()
                .userId("u-123")
                .username("alice")
                .userRole(User.UserRole.ADMIN)
                .build();

        JwtService.IssuedToken issued = jwtService.issue(user);
        assertThat(issued.token()).isNotBlank();
        assertThat(issued.tokenType()).isEqualTo("Bearer");

        JwtPrincipal principal = jwtService.parse(issued.token());
        assertThat(principal.userId()).isEqualTo("u-123");
        assertThat(principal.username()).isEqualTo("alice");
        assertThat(principal.role()).isEqualTo(User.UserRole.ADMIN);
        assertThat(principal.isGuest()).isFalse();
    }

    @Test
    void parseShouldRejectTamperedToken() {
        User user = User.builder().userId("u-1").username("bob").userRole(User.UserRole.USER).build();
        String token = jwtService.issue(user).token();

        // 篡改最后一个字符即破坏签名。
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> jwtService.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void parseShouldReturnNullForBlankToken() {
        assertThat(jwtService.parse(null)).isNull();
        assertThat(jwtService.parse("  ")).isNull();
    }

    @Test
    void extractBearerTokenShouldHandleSchemeAndCasing() {
        assertThat(jwtService.extractBearerToken("Bearer abc.def.ghi")).isEqualTo("abc.def.ghi");
        assertThat(jwtService.extractBearerToken("bearer abc")).isEqualTo("abc");
        assertThat(jwtService.extractBearerToken("Basic abc")).isNull();
        assertThat(jwtService.extractBearerToken(null)).isNull();
    }

    @Test
    void guestTokenShouldBeRecognized() {
        User guest = User.builder().userId("guest").username("guest").userRole(User.UserRole.GUEST).build();
        JwtPrincipal principal = jwtService.parse(jwtService.issue(guest).token());
        assertThat(principal.isGuest()).isTrue();
        assertThat(principal.role()).isEqualTo(User.UserRole.GUEST);
    }
}
