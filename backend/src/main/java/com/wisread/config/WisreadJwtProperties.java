package com.wisread.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "wisread.jwt")
public class WisreadJwtProperties {

    /**
     * 当前 JWT 签名密钥（HMAC-SHA 算法），用于签发与校验。
     * 来源：环境变量/配置文件 {@code wisread.jwt.secret-current}。
     * 必须至少 32 字节且不能是默认值，否则 {@link com.wisread.security.JwtService}
     * 启动即抛异常，防止使用弱密钥上线。
     */
    private String secretCurrent;

    /**
     * 上一代 JWT 签名密钥，仅用于校验（不签发）。
     * 来源：{@code wisread.jwt.secret-previous}，可空。
     * 密钥轮换时把旧密钥填到这里实现无感轮换：
     * 旧 token 在其剩余有效期内仍可校验，新 token 一律用 current 签发。
     */
    private String secretPrevious;

    /**
     * 访问令牌（Access Token）有效期。
     * 来源：{@code wisread.jwt.access-token-ttl}，默认 15 分钟。
     * 较短有效期降低令牌泄露后的风险，过期后用 Refresh Token 续期。
     */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    /**
     * 刷新令牌（Refresh Token）有效期的绝对上限。
     * 来源：{@code wisread.jwt.refresh-token-ttl}，默认 24 小时。
     * 语义：自登录（会话创建）起 24 小时后必须重新登录；
     * 会话轮换不延长该时间（非滑动窗口）。
     */
    private Duration refreshTokenTtl = Duration.ofHours(24);

    public String getSecretCurrent() {
        return secretCurrent;
    }

    public void setSecretCurrent(String secretCurrent) {
        this.secretCurrent = secretCurrent;
    }

    public String getSecretPrevious() {
        return secretPrevious;
    }

    public void setSecretPrevious(String secretPrevious) {
        this.secretPrevious = secretPrevious;
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
