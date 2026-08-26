package com.wisread.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Access Token 黑名单（基于 Redis）。
 * key 使用 token 的 jti，TTL 为该 token 的剩余有效期，到期自动清理。
 */
@Service
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "wisread:token:blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final JwtService jwtService;

    public TokenBlacklistService(StringRedisTemplate redisTemplate, JwtService jwtService) {
        this.redisTemplate = redisTemplate;
        this.jwtService = jwtService;
    }

    /** 将 access token 加入黑名单，TTL 取 token 剩余有效期；Redis 不可用时抛出异常，登出失败而非静默放行。 */
    public void blacklist(String token) {
        Duration ttl;
        try {
            ttl = jwtService.parseRemainingTtl(token);
        } catch (Exception e) {
            // 无效或已过期的 token 无需拉黑，也未触碰 Redis
            return;
        }
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        redisTemplate.opsForValue().set(key(token), "1", ttl);
    }

    /** 校验 token 是否被拉黑；Redis 不可用时抛异常，由过滤器按未认证处理（fail-closed）。 */
    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key(token)));
    }

    private String key(String token) {
        return KEY_PREFIX + jwtService.parseJti(token);
    }
}
