package com.wisread.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Access Token 黑名单（基于 Redis）。
 * key 使用 token 的 jti，TTL 为该 token 的剩余有效期，到期自动清理。
 */
@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);
    private static final String KEY_PREFIX = "wisread:token:blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final JwtService jwtService;

    public TokenBlacklistService(StringRedisTemplate redisTemplate, JwtService jwtService) {
        this.redisTemplate = redisTemplate;
        this.jwtService = jwtService;
    }

    /** 将 access token 加入黑名单，TTL 取 token 剩余有效期；Redis 不可用时降级（仅告警，不阻塞登出）。 */
    public void blacklist(String token) {
        try {
            Duration ttl = jwtService.parseRemainingTtl(token);
            if (ttl.isZero() || ttl.isNegative()) {
                return;
            }
            redisTemplate.opsForValue().set(key(token), "1", ttl);
        } catch (Exception e) {
            log.warn("Failed to blacklist access token: {}", e.getMessage());
        }
    }

    /** 校验 token 是否被拉黑；Redis 不可用时 fail-open 并记录告警。 */
    public boolean isBlacklisted(String token) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(token)));
        } catch (Exception e) {
            log.warn("Redis unavailable, skip blacklist check: {}", e.getMessage());
            return false;
        }
    }

    private String key(String token) {
        return KEY_PREFIX + jwtService.parseJti(token);
    }
}
