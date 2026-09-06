package com.wisread.security;

import com.wisread.config.WisreadSessionProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 会话令牌密文组件（AES-256-GCM）。
 *
 * <p>用途（FR-8）：refresh token 轮换时把最新一代 token 加密暂存于 Redis
 * （prev 条目的 tokenEnc 字段），PREVIOUS 宽限路径解密后通过 Set-Cookie
 * 重发同一个最新 token——既不新签发（保持轮换代不变），
 * 又让并发请求方的 Cookie 收敛到同一值。
 *
 * <p>安全性：Redis 数据单独泄露时无 {@code SESSION_CIPHER_KEY} 无法还原 token。
 * 密文格式 Base64(IV(12B) || ciphertext+tag)；同一明文两次加密密文不同（随机 IV）。
 */
@Component
public class TokenCipher {

    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenCipher(WisreadSessionProperties properties) {
        this.key = new SecretKeySpec(properties.decodeCipherKey(), "AES");
    }

    /** 加密明文，返回 Base64(IV || ciphertext)。 */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("token encryption failed", e);
        }
    }

    /** 解密 {@link #encrypt(String)} 产生的密文；任何篡改/密钥不匹配抛出异常。 */
    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BITS, combined, 0, IV_LENGTH_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            byte[] decrypted = cipher.doFinal(combined, IV_LENGTH_BYTES, combined.length - IV_LENGTH_BYTES);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("token decryption failed", e);
        }
    }

    /** 常量时间密钥等价判断辅助（测试用）。 */
    boolean sameKey(byte[] other) {
        return Arrays.equals(key.getEncoded(), other);
    }
}
