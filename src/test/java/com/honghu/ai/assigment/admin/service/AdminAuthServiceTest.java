package com.honghu.ai.assigment.admin.service;

import com.honghu.ai.assigment.entity.User;
import com.honghu.ai.assigment.repository.UserRepository;
import com.honghu.ai.assigment.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    private AdminAuthService adminAuthService;

    @BeforeEach
    void setUp() {
        adminAuthService = new AdminAuthService(userRepository, userService);
    }

    @Test
    void loginShouldReturnBearerTokenForAdminUser() {
        User admin = user("admin-1", "root", User.UserRole.ADMIN);
        when(userService.authenticate("root", "password")).thenReturn(admin);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        AdminAuthService.AdminLoginResult result = adminAuthService.login("root", "password");
        User resolved = adminAuthService.requireAdmin("Bearer " + result.token(), null);

        assertThat(result.token()).isNotBlank();
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.user()).isSameAs(admin);
        assertThat(resolved).isSameAs(admin);
    }

    @Test
    void loginShouldRejectNonAdminUser() {
        User normalUser = user("user-1", "alice", User.UserRole.USER);
        when(userService.authenticate("alice", "password")).thenReturn(normalUser);

        assertThatThrownBy(() -> adminAuthService.login("alice", "password"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void requireAdminShouldAcceptFallbackAdminUserIdForCompatibility() {
        User admin = user("admin-1", "root", User.UserRole.ADMIN);
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

        User resolved = adminAuthService.requireAdmin(null, "admin-1");

        assertThat(resolved).isSameAs(admin);
    }

    @Test
    void requireAdminShouldRejectFallbackNonAdminUserId() {
        User normalUser = user("user-1", "alice", User.UserRole.USER);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(normalUser));

        assertThatThrownBy(() -> adminAuthService.requireAdmin(null, "user-1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void logoutShouldInvalidateBearerToken() {
        User admin = user("admin-1", "root", User.UserRole.ADMIN);
        when(userService.authenticate("root", "password")).thenReturn(admin);

        AdminAuthService.AdminLoginResult result = adminAuthService.login("root", "password");
        adminAuthService.logout("Bearer " + result.token());

        assertThatThrownBy(() -> adminAuthService.requireAdmin("Bearer " + result.token(), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private User user(String userId, String username, User.UserRole role) {
        return User.builder()
                .userId(userId)
                .username(username)
                .password("password")
                .userStatus(User.UserStatus.ACTIVE)
                .userRole(role)
                .build();
    }
}
