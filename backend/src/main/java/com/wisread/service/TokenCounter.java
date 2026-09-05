package com.wisread.service;

import org.springframework.stereotype.Component;

/**
 * Token 估算工具组件。
 * 实现要点：不做真正的分词（如 BPE），而是采用 CJK 感知的轻量估算——
 * CJK 字符（汉字/全角符号）按 1 token 计，非 CJK（英文/数字/半角标点）按约 4 字符 = 1 token 计。
 * 这样对纯中文、纯英文及中英混合文本均能得到接近真实 BPE 的估算：
 *  - 中文：1 字 ≈ 1~2 token，按 1 计最贴近；
 *  - 英文：约 4 字符 = 1 token，沿用经验值。
 * 零依赖、开销极低，适用于分块阈值判断与用量统计。
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
        // CJK 与非 CJK 分别按各自密度估算后相加，混合文本也准
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        return Math.max(1, cjk + other / 4);
    }

    /**
     * 判定一个字符是否属于 CJK 范围（汉字/兼容汉字/CJK 标点/全角符号）。
     * 这些字符在 BPE 分词中通常 1 字 ≈ 1 token，故按 1 计最贴近真实。
     */
    private boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)   // CJK 统一汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // CJK 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF)  // CJK 兼容汉字
                || (c >= 0x3000 && c <= 0x303F)  // CJK 标点符号
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角 ASCII/半全角符号
    }
}
