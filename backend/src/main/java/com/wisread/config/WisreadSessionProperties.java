package com.wisread.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;

/**
 * 会话安全配置。
 *
 * <p>对应需求 FR-8：refresh token 轮换时以 AES-256-GCM 密文暂存最新一代令牌，
 * 供 PREVIOUS 宽限路径解密重发（多标签页并发刷新竞态修复）。
 */
@ConfigurationProperties(prefix = "wisread.session")
public class WisreadSessionProperties {

    /**
     * 会话密文密钥，Base64 编码的 32 字节（AES-256-GCM）。
     * 用于加密暂存 Redis 中的最新一代 refresh token（tokenEnc 字段）。
     * Redis 数据单独泄露时无此密钥无法还原 token。
     * 密钥更新只影响更新时点的在途宽限场景（旧密文随会话过期），
     * 无需专门迁移流程。
     */
    private String cipherKey;

    public String getCipherKey() {
        return cipherKey;
    }

    public void setCipherKey(String cipherKey) {
        this.cipherKey = cipherKey;
    }

    /** 解码并校验密钥长度（32 字节），不合法直接抛异常阻止启动。 */
    public byte[] decodeCipherKey() {
        if (cipherKey == null || cipherKey.isBlank()) {
            throw new IllegalStateException("SESSION_CIPHER_KEY must be set (base64, 32 bytes)");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(cipherKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("SESSION_CIPHER_KEY is not valid base64", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "SESSION_CIPHER_KEY must decode to 32 bytes for AES-256, got " + decoded.length);
        }
        return decoded;
    }
}
