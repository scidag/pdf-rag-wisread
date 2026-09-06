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
 *
 * <p>安全设计：
 * <ul>
 *   <li>令牌类型隔离：每个 token 携带 {@code typ} 声明（access/refresh），
 *       防止 refresh token 冒充 access token 直接调用受保护接口（审计 H1）。</li>
 *   <li>双密钥轮换：current 密钥负责签发与校验，previous 密钥仅校验，
 *       支持密钥无感轮换（审计 H2 / FR-4）。</li>
 * </ul>
 */
@Service
public class JwtService {

    // 自定义声明名，用于承载用户角色集合
    private static final String CLAIM_ROLES = "roles";
    // 令牌类型声明名：access / refresh（FR-1）
    public static final String CLAIM_TYP = "typ";
    public static final String TYP_ACCESS = "access";
    public static final String TYP_REFRESH = "refresh";
    // 开发环境默认弱密钥，线上必须替换，否则启动报错
    private static final String DEV_SECRET = "wisread-dev-secret-change-me-please-32bytes";

    private final SecretKey currentKey;
    private final SecretKey previousKey;
    private final WisreadJwtProperties properties;

    /**
     * 构造时校验密钥安全性并生成 HMAC 签名密钥。
     * 若 current 密钥未配置、为空或与默认开发密钥相同，则抛异常阻止启动；
     * previous 密钥可选，配置时同样要求非空非默认值。
     */
    public JwtService(WisreadJwtProperties properties) {
        this.properties = properties;
        String current = properties.getSecretCurrent();
        if (current == null || current.isBlank() || DEV_SECRET.equals(current)) {
            throw new IllegalStateException(
                    "JWT_SECRET_CURRENT must be set to a non-default value of at least 32 bytes");
        }
        this.currentKey = Keys.hmacShaKeyFor(current.getBytes(StandardCharsets.UTF_8));

        String previous = properties.getSecretPrevious();
        if (previous == null || previous.isBlank()) {
            this.previousKey = null;
        } else {
            if (DEV_SECRET.equals(previous)) {
                throw new IllegalStateException(
                        "JWT_SECRET_PREVIOUS must not be the default dev secret");
            }
            this.previousKey = Keys.hmacShaKeyFor(previous.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 创建访问令牌（Access Token）。
     * 携带用户 ID、用户名与角色，typ=access，有效期较短，用于常规接口鉴权。
     */
    public String createAccessToken(Long userId, String username, Set<String> roles) {
        return buildToken(userId, username, roles, properties.getAccessTokenTtl().toMillis(), TYP_ACCESS);
    }

    /**
     * 创建刷新令牌（Refresh Token）。
     * typ=refresh，有效期较长，仅用于换取新令牌。
     */
    public String createRefreshToken(Long userId, String username, Set<String> roles) {
        return buildToken(userId, username, roles, properties.getRefreshTokenTtl().toMillis(), TYP_REFRESH);
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
     * 解析令牌的 typ 声明。
     * 旧版本签发的 token 无该声明，返回 null（兼容期由调用方决定是否放行）。
     */
    public String parseType(String token) {
        Object typ = parseClaims(token).get(CLAIM_TYP);
        return typ == null ? null : typ.toString();
    }

    /**
     * 通用令牌构建方法。
     * 生成带唯一 jti、令牌类型、用户主体、用户名与角色声明，并设定签发与过期时间后签名。
     * 签发一律使用 current 密钥（previous 仅校验）。
     */
    private String buildToken(Long userId, String username, Set<String> roles, long ttlMillis, String typ) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString()) // 唯一标识，供黑名单使用
                .subject(String.valueOf(userId)) // 以用户 ID 作为主体
                .claim(CLAIM_TYP, typ) // 令牌类型，防止 refresh 冒充 access
                .claim("username", username)
                .claim(CLAIM_ROLES, roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .signWith(currentKey)
                .compact();
    }

    /**
     * 校验签名并解析令牌声明（payload）。
     * 先用 current 密钥校验，失败且配置了 previous 密钥时回退到 previous
     * （密钥无感轮换）；两把密钥都失败则抛出异常，由调用方捕获处理。
     */
    public Claims parseClaims(String token) {
        try {
            return doParse(currentKey, token);
        } catch (io.jsonwebtoken.JwtException currentFailure) {
            if (previousKey != null) {
                try {
                    return doParse(previousKey, token);
                } catch (io.jsonwebtoken.JwtException ignored) {
                    // previous 也失败，抛出原始异常
                }
            }
            throw currentFailure;
        }
    }

    private Claims doParse(SecretKey key, String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
