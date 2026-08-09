package com.wisread.security;

import com.wisread.config.WisreadJwtProperties;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
        String token = jwtService.createAccessToken(42L, "alice");

        Long userId = jwtService.parseUserId(token);

        assertThat(userId).isEqualTo(42L);
    }

    @Test
    void invalidTokenIsRejected() {
        assertThatThrownBy(() -> jwtService.parseUserId("not-a-jwt"))
                .isInstanceOf(JwtException.class);
    }
}
