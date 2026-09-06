package com.wisread.security;

import com.wisread.config.WisreadSessionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCipherTest {

    private TokenCipher cipher;

    @BeforeEach
    void setUp() {
        WisreadSessionProperties properties = new WisreadSessionProperties();
        // Base64 of 32 bytes
        properties.setCipherKey(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes()));
        cipher = new TokenCipher(properties);
    }

    @Test
    void encryptDecryptRoundTrips() {
        String token = UUID.randomUUID().toString();

        String encrypted = cipher.encrypt(token);

        assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
    }

    @Test
    void samePlaintextProducesDifferentCiphertext() {
        String token = "same-token";

        String first = cipher.encrypt(token);
        String second = cipher.encrypt(token);

        // 随机 IV：两次密文不同，且均能正确解密
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(token);
        assertThat(cipher.decrypt(second)).isEqualTo(token);
    }

    @Test
    void tamperedCiphertextFails() {
        String encrypted = cipher.encrypt("secret-token");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01; // 篡改末字节

        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyFailsToDecrypt() {
        String encrypted = cipher.encrypt("secret-token");

        WisreadSessionProperties other = new WisreadSessionProperties();
        other.setCipherKey(Base64.getEncoder().encodeToString(
                "ffffffffffffffffffffffffffffffff".getBytes()));
        TokenCipher wrong = new TokenCipher(other);

        assertThatThrownBy(() -> wrong.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidKeyLength() {
        WisreadSessionProperties shortKey = new WisreadSessionProperties();
        shortKey.setCipherKey(Base64.getEncoder().encodeToString("too-short".getBytes()));
        assertThatThrownBy(() -> new TokenCipher(shortKey))
                .isInstanceOf(IllegalStateException.class);

        WisreadSessionProperties blank = new WisreadSessionProperties();
        assertThatThrownBy(() -> new TokenCipher(blank))
                .isInstanceOf(IllegalStateException.class);
    }
}
