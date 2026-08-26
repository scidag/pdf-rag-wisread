package com.wisread.service;

import com.wisread.config.WisreadJwtProperties;
import com.wisread.dto.AuthResponse;
import com.wisread.dto.LoginRequest;
import com.wisread.dto.RegisterRequest;
import com.wisread.entity.User;
import com.wisread.entity.UserSession;
import com.wisread.exception.ApiException;
import com.wisread.repository.UserRepository;
import com.wisread.repository.UserSessionRepository;
import com.wisread.security.JwtService;
import com.wisread.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSessionRepository userSessionRepository;

    @Mock
    private JwtService jwtService;

    private WisreadJwtProperties jwtProperties;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwtProperties = new WisreadJwtProperties();
        jwtProperties.setSecret("test-secret");
        jwtProperties.setAccessTokenTtl(Duration.ofMinutes(15));
        jwtProperties.setRefreshTokenTtl(Duration.ofDays(7));
        authService = new AuthServiceImpl(userRepository, userSessionRepository, jwtService, jwtProperties);
    }

    @Test
    void registerCreatesUserAndReturnsTokens() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(jwtService.createAccessToken(1L, "alice", Set.of("USER"))).thenReturn("access");
        when(jwtService.createRefreshToken(1L, "alice", Set.of("USER"))).thenReturn("refresh");
        when(userRepository.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 1L);
            return 1;
        });

        AuthResponse response = authService.register(
                new RegisterRequest("alice", "alice@example.com", "password123")
        );

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        assertThat(response.user().username()).isEqualTo("alice");
        verify(userRepository).insert(any(User.class));
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("alice", "alice@example.com", "password123")
        )).isInstanceOf(ApiException.class)
                .hasMessage("email already exists");
    }

    @Test
    void loginReturnsTokensWithValidPassword() {
        User user = userWithId(1L);
        user.setEmail("alice@example.com");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("password123"));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(jwtService.createAccessToken(1L, "alice", Set.of("USER"))).thenReturn("access");
        when(jwtService.createRefreshToken(1L, "alice", Set.of("USER"))).thenReturn("refresh");

        AuthResponse response = authService.login(
                new LoginRequest("alice@example.com", "password123"),
                "Chrome",
                "127.0.0.1"
        );

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        verify(userSessionRepository).insert(any(UserSession.class));
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = userWithId(1L);
        user.setEmail("alice@example.com");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("password123"));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("alice@example.com", "wrong-password"),
                "Chrome",
                "127.0.0.1"
        )).isInstanceOf(ApiException.class)
                .hasMessage("invalid credentials");
    }

    @Test
    void loginRejectsDisabledUser() {
        User user = userWithId(1L);
        user.setEmail("alice@example.com");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("password123"));
        user.setStatus((short) 0);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("alice@example.com", "password123"),
                "Chrome",
                "127.0.0.1"
        )).isInstanceOf(ApiException.class)
                .hasMessage("account disabled");
    }

    @Test
    void refreshDetectsReusedTokenAndRevokesSessions() {
        when(userSessionRepository.findByRefreshTokenHashAndExpiresAtAfter(anyString(), any()))
                .thenReturn(Optional.empty());
        UserSession stale = new UserSession();
        stale.setUserId(1L);
        when(userSessionRepository.findByPreviousRefreshTokenHashAndExpiresAtAfter(anyString(), any()))
                .thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> authService.refresh("old-refresh-token", "Chrome", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("refresh token reuse detected");
        verify(userSessionRepository).deleteByUserId(1L);
    }

    @Test
    void refreshRejectsUnknownTokenWithoutRevocation() {
        when(userSessionRepository.findByRefreshTokenHashAndExpiresAtAfter(anyString(), any()))
                .thenReturn(Optional.empty());
        when(userSessionRepository.findByPreviousRefreshTokenHashAndExpiresAtAfter(anyString(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("unknown-token", "Chrome", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("invalid refresh token");
        verify(userSessionRepository, never()).deleteByUserId(any(Long.class));
    }

    private User userWithId(Long id) {
        User user = new User();
        user.setUsername("alice");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
