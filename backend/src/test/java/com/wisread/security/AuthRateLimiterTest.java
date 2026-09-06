package com.wisread.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new AuthRateLimiter(redis, new AuthMetrics(null));
    }

    @Test
    void allowsWithinLimitAndBlocksBeyond() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        // 模拟固定窗口计数：前 10 次 1..10，第 11 次 11
        long[] counter = {0};
        when(valueOperations.increment(anyString())).thenAnswer(inv -> ++counter[0]);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("auth:1.2.3.4", 10, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(limiter.tryAcquire("auth:1.2.3.4", 10, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void fallsBackToLocalBucketWhenRedisDown() {
        // FR-9：Redis 故障时降级 Caffeine 本地兜底，认证接口不裸奔
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("auth:9.9.9.9", 10, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(limiter.tryAcquire("auth:9.9.9.9", 10, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void localFallbackKeysAreIsolated() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        // 不同 IP 的本地兜底计数互不影响
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("auth:1.1.1.1", 10, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(limiter.tryAcquire("auth:2.2.2.2", 10, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("auth:1.1.1.1", 10, Duration.ofMinutes(1))).isFalse();
    }
}
