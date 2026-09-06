package com.wisread.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCounterTest {

    private final TokenCounter tokenCounter = new TokenCounter();

    @Test
    void emptyTextReturnsOne() {
        assertThat(tokenCounter.count("")).isEqualTo(1);
        assertThat(tokenCounter.count("   ")).isEqualTo(1);
    }

    @Test
    void countsCjkPerCharAndOtherPerFour() {
        // 13 个 CJK 字符按 1 token/字计
        assertThat(tokenCounter.count("人工智能学习路线与职业规划")).isEqualTo(13);
        // 12 个英文字符按 4 字符/token 计
        assertThat(tokenCounter.count("hello wisread")).isEqualTo(3);
        // 混合：4 个汉字 + 8 个英文字符 = 4 + 2
        assertThat(tokenCounter.count("人工智能wisread1")).isEqualTo(6);
    }
}
