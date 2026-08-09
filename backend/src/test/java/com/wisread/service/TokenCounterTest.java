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
    void countsTextAsLengthDividedByFour() {
        assertThat(tokenCounter.count("人工智能学习路线与职业规划")).isEqualTo(3);
    }
}
