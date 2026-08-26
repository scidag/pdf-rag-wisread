package com.wisread.security;

import com.wisread.config.WisreadJwtProperties;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        WisreadJwtProperties properties = new WisreadJwtProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef0123456789abcdef");
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        properties.setRefreshTokenTtl(Duration.ofDays(7));
        jwtService = new JwtService(properties);
    }

    @Test
    void createAndParseAccessTokenRoundTripsUserId() {
        String token = jwtService.createAccessToken(42L, "alice", Set.of("USER", "ADMIN"));

        Long userId = jwtService.parseUserId(token);

        assertThat(userId).isEqualTo(42L);
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
        blank.setSecret("   ");
        assertThatThrownBy(() -> new JwtService(blank))
                .isInstanceOf(IllegalStateException.class);

        WisreadJwtProperties defaults = new WisreadJwtProperties();
        defaults.setSecret("wisread-dev-secret-change-me-please-32bytes");
        assertThatThrownBy(() -> new JwtService(defaults))
                .isInstanceOf(IllegalStateException.class);
    }
}
