package com.wisread.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 认证接口限流器（FR-9）。
 *
 * <p>一级：Redis 固定窗口计数（INCR + EXPIRE），多实例一致；
 * 二级：Redis 不可用时降级 Caffeine 本地固定窗口（单实例阈值），
 * 认证接口不允许因 Redis 故障而完全裸奔（防暴力破解单点）。
 */
@Component
public class AuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);
    private static final String KEY_PREFIX = "wisread:rl:";

    private final StringRedisTemplate redis;
    private final AuthMetrics metrics;

    // 本地兜底计数：key 过期即窗口重置（近似固定窗口，仅作降级）
    private final Cache<String, AtomicLong> localBuckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    public AuthRateLimiter(StringRedisTemplate redis, AuthMetrics metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    /**
     * 尝试获取一次配额。
     *
     * @param key    限流维度（如 "auth:1.2.3.4"）
     * @param limit  窗口内允许的最大次数
     * @param window 窗口长度
     * @return true 表示放行；false 表示已超限
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        String redisKey = KEY_PREFIX + key;
        try {
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, window);
            }
            return count == null || count <= limit;
        } catch (Exception e) {
            log.warn("Redis rate limiter unavailable, falling back to local bucket, key={}", key, e);
            metrics.rateLimitFallback();
            return localTryAcquire(key, limit);
        }
    }

    private boolean localTryAcquire(String key, int limit) {
        AtomicLong counter = localBuckets.get(key, k -> new AtomicLong());
        return counter != null && counter.incrementAndGet() <= limit;
    }
}
