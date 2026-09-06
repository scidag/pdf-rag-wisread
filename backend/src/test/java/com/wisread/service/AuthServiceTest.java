package com.wisread.service;

import com.wisread.config.WisreadAuthProperties;
import com.wisread.config.WisreadJwtProperties;
import com.wisread.dto.AuthResponse;
import com.wisread.dto.LoginRequest;
import com.wisread.dto.RegisterRequest;
import com.wisread.entity.User;
import com.wisread.exception.ApiException;
import com.wisread.repository.UserRepository;
import com.wisread.security.AuthMetrics;
import com.wisread.security.JwtService;
import com.wisread.security.TokenBlacklistService;
import com.wisread.security.TokenCipher;
import com.wisread.security.UserSessionStore;
import com.wisread.security.UserSessionStore.GraceContext;
import com.wisread.security.UserSessionStore.LocateResult;
import com.wisread.security.UserSessionStore.RotateOutcome;
import com.wisread.security.UserSessionStore.SessionCreate;
import com.wisread.security.UserSessionStore.SessionSnapshot;
import com.wisread.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSessionStore sessionStore;

    @Mock
    private JwtService jwtService;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private TokenCipher tokenCipher;

    private WisreadJwtProperties jwtProperties;
    private WisreadAuthProperties authProperties;
    private AuthService authService;

    private static final Instant NOW = Instant.now();

    @BeforeEach
    void setUp() {
        jwtProperties = new WisreadJwtProperties();
        jwtProperties.setSecretCurrent("test-secret-0123456789abcdef0123456789");
        jwtProperties.setAccessTokenTtl(Duration.ofMinutes(15));
        jwtProperties.setRefreshTokenTtl(Duration.ofHours(24));
        authProperties = new WisreadAuthProperties();
        authService = new AuthServiceImpl(userRepository, jwtService, jwtProperties,
                authProperties, sessionStore, tokenBlacklistService, tokenCipher,
                new AuthMetrics(null));
    }

    @Test
    void registerCreatesUserAndSessionWithAbsoluteExpiry() {
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
        assertThat(response.user().username()).isEqualTo("alice");
        // FR-5：会话绝对有效期 = 创建时刻 + 24h（固定，轮换不延长）
        ArgumentCaptor<SessionCreate> captor = ArgumentCaptor.forClass(SessionCreate.class);
        verify(sessionStore).create(captor.capture());
        SessionCreate cmd = captor.getValue();
        assertThat(cmd.refreshToken()).isEqualTo("refresh");
        assertThat(cmd.maxExpireAt()).isAfter(Instant.now().plus(Duration.ofHours(23)));
        assertThat(cmd.maxExpireAt()).isBefore(Instant.now().plus(Duration.ofHours(25)));
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
                "Chrome", "127.0.0.1"
        );

        assertThat(response.accessToken()).isEqualTo("access");
        verify(sessionStore).create(any(SessionCreate.class));
    }

    @Test
    void loginUnknownEmailStillFailsWithUnifiedMessage() {
        // FR-6：用户不存在时同样返回统一 401（哑哈希时延抹平不可直接断言，验证行为一致）
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("ghost@example.com", "password123"),
                "Chrome", "127.0.0.1"
        )).isInstanceOf(ApiException.class)
                .hasMessage("invalid credentials");
        verify(sessionStore, never()).create(any(SessionCreate.class));
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = userWithId(1L);
        user.setEmail("alice@example.com");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("password123"));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("alice@example.com", "wrong-password"),
                "Chrome", "127.0.0.1"
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
                "Chrome", "127.0.0.1"
        )).isInstanceOf(ApiException.class)
                .hasMessage("account disabled");
    }

    @Test
    void refreshRejectsAccessTokenTyp() {
        // FR-1：access token 不能用于刷新接口
        when(jwtService.parseType("token")).thenReturn("access");

        assertThatThrownBy(() -> authService.refresh("token", "Chrome", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("invalid refresh token");
        verify(sessionStore, never()).locate(anyString());
    }

    @Test
    void refreshRejectsBlacklistedToken() {
        // FR-2：登出后 refresh token jti 已入黑名单
        when(jwtService.parseType("token")).thenReturn("refresh");
        when(tokenBlacklistService.isBlacklisted("token")).thenReturn(true);

        assertThatThrownBy(() -> authService.refresh("token", "Chrome", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("invalid refresh token");
        verify(sessionStore, never()).locate(anyString());
    }

    @Test
    void refreshAcceptsLegacyTokenWithoutTyp() {
        // FR-1.4：R1 过渡期接受无 typ 的旧 token（access 侧不受影响）
        when(jwtService.parseType("legacy-token")).thenReturn(null);
        when(tokenBlacklistService.isBlacklisted("legacy-token")).thenReturn(false);
        when(sessionStore.locate("legacy-token"))
                .thenReturn(new LocateResult.Found(snapshot(1L, NOW.minusSeconds(60))));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(jwtService.createRefreshToken(1L, "alice", Set.of("USER"))).thenReturn("new-refresh");
        when(jwtService.createAccessToken(1L, "alice", Set.of("USER"))).thenReturn("access");
        when(sessionStore.rotate(any(SessionSnapshot.class), eq("legacy-token"),
                eq("new-refresh"), eq("Chrome"), eq("127.0.0.1")))
                .thenReturn(new RotateOutcome.Found(snapshot(1L, NOW)));

        AuthResponse response = authService.refresh("legacy-token", "Chrome", "127.0.0.1");

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    void refreshRotatesAndReturnsNewTokens() {
        SessionSnapshot snapshot = snapshot(1L, NOW);
        when(jwtService.parseType("token")).thenReturn("refresh");
        when(tokenBlacklistService.isBlacklisted("token")).thenReturn(false);
        when(sessionStore.locate("token")).thenReturn(new LocateResult.Found(snapshot));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(jwtService.createRefreshToken(1L, "alice", Set.of("USER"))).thenReturn("new-refresh");
        when(jwtService.createAccessToken(1L, "alice", Set.of("USER"))).thenReturn("access");
        when(sessionStore.rotate(snapshot, "token", "new-refresh", "Chrome", "127.0.0.1"))
                .thenReturn(new RotateOutcome.Found(snapshot));

        AuthResponse response = authService.refresh("token", "Chrome", "127.0.0.1");

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    void refreshRotateReplayRevokesAllSessions() {
        SessionSnapshot snapshot = snapshot(1L, NOW);
        when(jwtService.parseType("token")).thenReturn("refresh");
        when(tokenBlacklistService.isBlacklisted("token")).thenReturn(false);
        when(sessionStore.locate("token")).thenReturn(new LocateResult.Found(snapshot));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(jwtService.createRefreshToken(1L, "alice", Set.of("USER"))).thenReturn("new-refresh");
        // 轮换竞态命中上一代：判定重放
        when(sessionStore.rotate(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new RotateOutcome.Replay(1L));

        assertThatThrownBy(() -> authService.refresh("token", "Chrome", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("refresh token reuse detected");
        verify(sessionStore).revokeAll(1L);
    }

    @Test
    void refreshLocateReplayRevokesAllSessions() {
        when(jwtService.parseType("token")).thenReturn("refresh");
        when(tokenBlacklistService.isBlacklisted("token")).thenReturn(false);
        when(sessionStore.locate("token")).thenReturn(new LocateResult.Replay(1L));

        assertThatThrownBy(() -> authService.refresh("token", "Chrome", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("refresh token reuse detected");
        verify(sessionStore).revokeAll(1L);
    }

    @Test
    void refreshRejectsUnknownTokenWithoutRevocation() {
        when(jwtService.parseType("unknown-token")).thenReturn("refresh");
        when(tokenBlacklistService.isBlacklisted("unknown-token")).thenReturn(false);
        when(sessionStore.locate("unknown-token")).thenReturn(new LocateResult.NotFound());

        assertThatThrownBy(() -> authService.refresh("unknown-token", "Chrome", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("invalid refresh token");
        verify(sessionStore, never()).revokeAll(any(Long.class));
    }

    @Test
    void refreshGraceReissuesLatestTokenWithSameValue() {
        // FR-8：PREVIOUS 宽限——重发同一最新 token（Cookie 收敛），不新签发
        GraceContext ctx = new GraceContext("newHash", "Chrome", "127.0.0.1",
                NOW.minusSeconds(30), "enc-token");
        when(jwtService.parseType("old")).thenReturn("refresh");
        when(tokenBlacklistService.isBlacklisted("old")).thenReturn(false);
        when(sessionStore.locate("old")).thenReturn(new LocateResult.Previous(ctx));
        when(tokenCipher.decrypt("enc-token")).thenReturn("latest-token");
        when(sessionStore.locate("latest-token"))
                .thenReturn(new LocateResult.Found(snapshot(1L, NOW)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(jwtService.createAccessToken(1L, "alice", Set.of("USER"))).thenReturn("access");

        AuthResponse response = authService.refresh("old", "Chrome", "127.0.0.1");

        // 宽限返回：refreshToken 是解密出的同一最新 token，而非新签发
        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("latest-token");
        verify(jwtService, never()).createRefreshToken(any(Long.class), anyString(), any());
        verify(sessionStore, never()).revokeAll(any(Long.class));
    }

    @Test
    void refreshGraceWithDeviceMismatchRevokesAllSessions() {
        GraceContext ctx = new GraceContext("newHash", "Chrome-Desktop", "127.0.0.1",
                NOW.minusSeconds(30), "enc-token");
        when(jwtService.parseType("old")).thenReturn("refresh");
        when(tokenBlacklistService.isBlacklisted("old")).thenReturn(false);
        when(sessionStore.locate("old")).thenReturn(new LocateResult.Previous(ctx));
        when(tokenCipher.decrypt("enc-token")).thenReturn("latest-token");
        when(sessionStore.locate("latest-token"))
                .thenReturn(new LocateResult.Found(snapshot(1L, NOW)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));

        assertThatThrownBy(() -> authService.refresh("old", "Firefox-Attacker", "8.8.8.8"))
                .isInstanceOf(ApiException.class)
                .hasMessage("refresh token reuse detected");
        verify(sessionStore).revokeAll(1L);
    }

    @Test
    void refreshGraceOutsideWindowRevokesAllSessions() {
        GraceContext ctx = new GraceContext("newHash", "Chrome", "127.0.0.1",
                NOW.minusSeconds(3600), "enc-token"); // 超过 5 分钟宽限窗口
        when(jwtService.parseType("old")).thenReturn("refresh");
        when(tokenBlacklistService.isBlacklisted("old")).thenReturn(false);
        when(sessionStore.locate("old")).thenReturn(new LocateResult.Previous(ctx));
        when(tokenCipher.decrypt("enc-token")).thenReturn("latest-token");
        when(sessionStore.locate("latest-token"))
                .thenReturn(new LocateResult.Found(snapshot(1L, NOW)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));

        assertThatThrownBy(() -> authService.refresh("old", "Chrome", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("refresh token reuse detected");
        verify(sessionStore).revokeAll(1L);
    }

    @Test
    void refreshGraceWithIpChangeStillAllowedByDefault() {
        // IP 漂移（WiFi→4G）默认放行：仅 device 必须一致
        GraceContext ctx = new GraceContext("newHash", "Chrome", "10.0.0.1",
                NOW.minusSeconds(30), "enc-token");
        when(jwtService.parseType("old")).thenReturn("refresh");
        when(tokenBlacklistService.isBlacklisted("old")).thenReturn(false);
        when(sessionStore.locate("old")).thenReturn(new LocateResult.Previous(ctx));
        when(tokenCipher.decrypt("enc-token")).thenReturn("latest-token");
        when(sessionStore.locate("latest-token"))
                .thenReturn(new LocateResult.Found(snapshot(1L, NOW)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(activeUser()));
        when(jwtService.createAccessToken(1L, "alice", Set.of("USER"))).thenReturn("access");

        AuthResponse response = authService.refresh("old", "Chrome", "223.5.5.5");

        assertThat(response.refreshToken()).isEqualTo("latest-token");
        verify(sessionStore, never()).revokeAll(any(Long.class));
    }

    @Test
    void refreshGraceFailsWhenTargetSessionDead() {
        // 登出/吊销后目标会话不存在：旧令牌不可经宽限路径复活
        GraceContext ctx = new GraceContext("newHash", "Chrome", "127.0.0.1",
                NOW.minusSeconds(30), "enc-token");
        when(jwtService.parseType("old")).thenReturn("refresh");
        when(tokenBlacklistService.isBlacklisted("old")).thenReturn(false);
        when(sessionStore.locate("old")).thenReturn(new LocateResult.Previous(ctx));
        when(tokenCipher.decrypt("enc-token")).thenReturn("latest-token");
        when(sessionStore.locate("latest-token")).thenReturn(new LocateResult.NotFound());

        assertThatThrownBy(() -> authService.refresh("old", "Chrome", "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("invalid refresh token");
        verify(sessionStore, never()).revokeAll(any(Long.class));
    }

    @Test
    void logoutDeletesSession() {
        authService.logout("token");
        verify(sessionStore).delete("token");
    }

    @Test
    void logoutToleratesNullToken() {
        authService.logout(null);
        verify(sessionStore, never()).delete(anyString());
    }

    private SessionSnapshot snapshot(Long userId, Instant maxExpireAt) {
        return new SessionSnapshot(userId, "Chrome", "127.0.0.1",
                maxExpireAt.minus(Duration.ofHours(24)), maxExpireAt);
    }

    private User userWithId(Long id) {
        User user = new User();
        user.setUsername("alice");
        user.setStatus((short) 1);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private User activeUser() {
        return userWithId(1L);
    }
}
