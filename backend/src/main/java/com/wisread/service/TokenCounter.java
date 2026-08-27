package com.wisread.service;

import org.springframework.stereotype.Component;

/**
 * Token 估算工具组件。
 * 实现要点：不做真正的分词（如 BPE），而是采用“字符数 / 4”的轻量经验估算。
 * 对中文、英文混合文本均有较好近似（英文约 4 字符 = 1 token），足以用于
 * 分块阈值判断与用量统计，且零依赖、开销极低。
 */
@Component
public class TokenCounter {

    /**
     * 估算文本的 token 数量。
     * 为什么返回至少 1：避免空文本在分块/计费计算中被当作 0 而绕过最小块判断。
     *
     * @param text 待估算文本
     * @return 估算的 token 数（最小为 1）
     */
    public int count(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        // 经验公式：每 4 个字符约等于 1 个 token
        return Math.max(1, text.length() / 4);
    }
}
