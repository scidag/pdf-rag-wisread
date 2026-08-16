package com.wisread.security;

import com.wisread.config.WisreadJwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class JwtService {

    private static final String CLAIM_ROLES = "roles";

    private final SecretKey key;
    private final WisreadJwtProperties properties;

    public JwtService(WisreadJwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId, String username, Set<String> roles) {
        return buildToken(userId, username, roles, properties.getAccessTokenTtl().toMillis());
    }

    public String createRefreshToken(Long userId, String username, Set<String> roles) {
        return buildToken(userId, username, roles, properties.getRefreshTokenTtl().toMillis());
    }

    public Long parseUserId(String token) {
        Claims claims = parseClaims(token);
        return Long.valueOf(claims.getSubject());
    }

    /** 取 token 的 jti，用于黑名单等场景。 */
    public String parseJti(String token) {
        return parseClaims(token).getId();
    }

    /** 取 token 剩余有效期；已过期则返回 ZERO。 */
    public Duration parseRemainingTtl(String token) {
        Duration remaining = Duration.between(Instant.now(), parseExpiration(token));
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public Instant parseExpiration(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    /** 将 token 中的 roles claim 转换为 Spring Security 的 GrantedAuthority（带 ROLE_ 前缀）。 */
    public List<GrantedAuthority> parseAuthorities(String token) {
        Object roles = parseClaims(token).get(CLAIM_ROLES);
        if (roles instanceof Collection<?> collection) {
            return collection.stream()
                    .map(Object::toString)
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        }
        return List.of();
    }

    private String buildToken(Long userId, String username, Set<String> roles, long ttlMillis) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim(CLAIM_ROLES, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .signWith(key)
                .compact();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
