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

/**
 * JWT 生成与解析服务。
 * 封装访问令牌/刷新令牌的创建、签名校验与声明（claims）解析，
 * 是“智阅”认证体系的核心工具。密钥与有效期来自 {@link WisreadJwtProperties}。
 */
@Service
public class JwtService {

    // 自定义声明名，用于承载用户角色集合
    private static final String CLAIM_ROLES = "roles";
    // 开发环境默认弱密钥，线上必须替换，否则启动报错
    private static final String DEV_SECRET = "wisread-dev-secret-change-me-please-32bytes";

    private final SecretKey key;
    private final WisreadJwtProperties properties;

    /**
     * 构造时校验密钥安全性并生成 HMAC 签名密钥。
     * 若密钥未配置、为空或与默认开发密钥相同，则抛异常阻止启动，
     * 防止使用弱密钥签发令牌。
     */
    public JwtService(WisreadJwtProperties properties) {
        this.properties = properties;
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank() || DEV_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to a non-default value of at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 创建访问令牌（Access Token）。
     * 携带用户 ID、用户名与角色，有效期较短，用于常规接口鉴权。
     */
    public String createAccessToken(Long userId, String username, Set<String> roles) {
        return buildToken(userId, username, roles, properties.getAccessTokenTtl().toMillis());
    }

    /**
     * 创建刷新令牌（Refresh Token）。
     * 有效期较长，仅用于在 Access Token 过期后换取新令牌。
     */
    public String createRefreshToken(Long userId, String username, Set<String> roles) {
        return buildToken(userId, username, roles, properties.getRefreshTokenTtl().toMillis());
    }

    /**
     * 从令牌中解析用户 ID（subject）。
     * 用于认证过滤器定位当前登录用户。
     */
    public Long parseUserId(String token) {
        Claims claims = parseClaims(token);
        return Long.valueOf(claims.getSubject());
    }

    /** 取 token 的 jti（唯一标识），用于黑名单等场景。 */
    public String parseJti(String token) {
        return parseClaims(token).getId();
    }

    /** 取 token 剩余有效期；已过期则返回 ZERO。 */
    public Duration parseRemainingTtl(String token) {
        Duration remaining = Duration.between(Instant.now(), parseExpiration(token));
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * 解析令牌的过期时间（Instant）。
     */
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

    /**
     * 通用令牌构建方法。
     * 生成带唯一 jti、用户主体、用户名与角色声明，并设定签发与过期时间后签名。
     */
    private String buildToken(Long userId, String username, Set<String> roles, long ttlMillis) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString()) // 唯一标识，供黑名单使用
                .subject(String.valueOf(userId)) // 以用户 ID 作为主体
                .claim("username", username)
                .claim(CLAIM_ROLES, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验签名并解析令牌声明（payload）。
     * 任何篡改或过期都会在此抛出异常，由调用方捕获处理。
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
