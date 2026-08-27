package com.wisread.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "wisread.jwt")
public class WisreadJwtProperties {

    /**
     * JWT 签名密钥（HMAC-SHA 算法）。
     * 来源：环境变量/配置文件 {@code wisread.jwt.secret}。
     * 必须至少 32 字节且不能是默认值，否则 {@link com.wisread.security.JwtService}
     * 启动即抛异常，防止使用弱密钥上线。
     */
    private String secret;

    /**
     * 访问令牌（Access Token）有效期。
     * 来源：{@code wisread.jwt.access-token-ttl}，默认 15 分钟。
     * 较短有效期降低令牌泄露后的风险，过期后用 Refresh Token 续期。
     */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    /**
     * 刷新令牌（Refresh Token）有效期。
     * 来源：{@code wisread.jwt.refresh-token-ttl}，默认 7 天。
     * 用于在 Access Token 过期后换取新令牌，避免频繁重新登录。
     */
    private Duration refreshTokenTtl = Duration.ofDays(7);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }
}
