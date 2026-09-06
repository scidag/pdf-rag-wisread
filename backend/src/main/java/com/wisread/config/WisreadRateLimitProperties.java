package com.wisread.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证接口限流配置（FR-9）。
 *
 * <p>计数存储于 Redis（多实例一致）；Redis 故障时降级到
 * Caffeine 本地兜底（单实例阈值），认证接口不允许完全裸奔。
 */
@ConfigurationProperties(prefix = "wisread.ratelimit")
public class WisreadRateLimitProperties {

    /** 登录/注册接口单 IP 每分钟允许的请求数。默认 10。 */
    private int authPerMinute = 10;

    /** 刷新接口单 IP 每分钟允许的请求数（刷新频率天然更高）。默认 30。 */
    private int refreshPerMinute = 30;

    public int getAuthPerMinute() {
        return authPerMinute;
    }

    public void setAuthPerMinute(int authPerMinute) {
        this.authPerMinute = authPerMinute;
    }

    public int getRefreshPerMinute() {
        return refreshPerMinute;
    }

    public void setRefreshPerMinute(int refreshPerMinute) {
        this.refreshPerMinute = refreshPerMinute;
    }
}
