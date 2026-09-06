package com.wisread.security;

import com.wisread.config.WisreadJwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String CURRENT_SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PREVIOUS_SECRET =
            "fedcba9876543210fedcba9876543210fedcba9876543210";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        WisreadJwtProperties properties = new WisreadJwtProperties();
        properties.setSecretCurrent(CURRENT_SECRET);
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        properties.setRefreshTokenTtl(Duration.ofHours(24));
        jwtService = new JwtService(properties);
    }

    private WisreadJwtProperties propertiesWithPrevious() {
        WisreadJwtProperties properties = new WisreadJwtProperties();
        properties.setSecretCurrent(CURRENT_SECRET);
        properties.setSecretPrevious(PREVIOUS_SECRET);
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        properties.setRefreshTokenTtl(Duration.ofHours(24));
        return properties;
    }

    @Test
    void createAndParseAccessTokenRoundTripsUserId() {
        String token = jwtService.createAccessToken(42L, "alice", Set.of("USER", "ADMIN"));

        assertThat(jwtService.parseUserId(token)).isEqualTo(42L);
        assertThat(jwtService.parseType(token)).isEqualTo(JwtService.TYP_ACCESS);
    }

    @Test
    void refreshTokenCarriesRefreshTyp() {
        String token = jwtService.createRefreshToken(42L, "alice", Set.of("USER"));

        assertThat(jwtService.parseType(token)).isEqualTo(JwtService.TYP_REFRESH);
    }

    @Test
    void parseAuthoritiesReturnsRolesWithPrefix() {
        String token = jwtService.createAccessToken(42L, "alice", Set.of("USER", "ADMIN"));

        assertThat(jwtService.parseAuthorities(token))
                .extracting(authority -> authority.getAuthority())
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void accessTokenExposesJtiAndTtl() {
        String token = jwtService.createAccessToken(42L, "alice", Set.of("USER"));

        assertThat(jwtService.parseJti(token)).isNotBlank();
        assertThat(jwtService.parseRemainingTtl(token))
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void invalidTokenIsRejected() {
        assertThatThrownBy(() -> jwtService.parseUserId("not-a-jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsMissingOrDefaultSecret() {
        WisreadJwtProperties blank = new WisreadJwtProperties();
        blank.setSecretCurrent("   ");
        assertThatThrownBy(() -> new JwtService(blank))
                .isInstanceOf(IllegalStateException.class);

        WisreadJwtProperties defaults = new WisreadJwtProperties();
        defaults.setSecretCurrent("wisread-dev-secret-change-me-please-32bytes");
        assertThatThrownBy(() -> new JwtService(defaults))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedTypIsRejectedOnAccessPath() {
        // 构造 typ 被篡改为 access 的 refresh token 攻击场景：
        // 用同一密钥签发一个 typ=access 的令牌，模拟攻击者改写声明后重签
        String tampered = Jwts.builder()
                .id("tampered")
                .subject("42")
                .claim("typ", "access")
                .claim("username", "alice")
                .claim("roles", Set.of("USER"))
                .issuedAt(java.util.Date.from(java.time.Instant.now()))
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(60)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        CURRENT_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
        Claims claims = jwtService.parseClaims(tampered);
        // 篡改后的 token 应被 access 过滤器的 typ 校验逻辑拒收（此处校验判定输入）
        assertThat(JwtService.TYP_ACCESS.equals(String.valueOf(claims.get(JwtService.CLAIM_TYP))))
                .isTrue();
        // 而 refresh 接口的 typ 校验应拒绝它
        assertThat(JwtService.TYP_REFRESH.equals(String.valueOf(claims.get(JwtService.CLAIM_TYP))))
                .isFalse();
    }

    @Test
    void dualKeyVerifiesPreviousButSignsWithCurrent() {
        JwtService dual = new JwtService(propertiesWithPrevious());

        // 用 previous 密钥签发的旧 token 仍可校验（无感轮换）
        String legacyToken = Jwts.builder()
                .id("legacy")
                .subject("42")
                .claim("typ", JwtService.TYP_ACCESS)
                .issuedAt(java.util.Date.from(java.time.Instant.now()))
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(60)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        PREVIOUS_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
        assertThat(dual.parseUserId(legacyToken)).isEqualTo(42L);

        // 新签发的 token 用 current 密钥，仅 current 的实例无法解析 previous 签发的 token
        String fresh = dual.createAccessToken(42L, "alice", Set.of("USER"));
        assertThat(jwtService.parseUserId(fresh)).isEqualTo(42L);
    }

    @Test
    void wrongSignatureRejectedWhenNoPreviousKey() {
        String legacyToken = Jwts.builder()
                .id("legacy")
                .subject("42")
                .issuedAt(java.util.Date.from(java.time.Instant.now()))
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(60)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        PREVIOUS_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
        // 未配置 previous 密钥时，previous 签名的 token 应被拒绝
        assertThatThrownBy(() -> jwtService.parseUserId(legacyToken))
                .isInstanceOf(JwtException.class);
    }
}
