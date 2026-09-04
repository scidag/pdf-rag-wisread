package com.wisread.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceImplErrorTest {

    @Test
    void quotaExhaustedIsTranslatedForUser() {
        Throwable error = new RuntimeException(
                "429 - {\"error\":{\"message\":\"Insufficient Quota\",\"type\":\"insufficient_quota\"}}"
        );

        assertThat(ChatServiceImpl.translateErrorMessage(error))
                .isEqualTo("AI 模型额度已用完，请充值后再试");
    }

    @Test
    void quotaDetectedThroughCauseChain() {
        Throwable error = new RuntimeException(
                "upstream failure",
                new IllegalStateException("402 - 余额不足，请充值后继续使用")
        );

        assertThat(ChatServiceImpl.translateErrorMessage(error))
                .isEqualTo("AI 模型额度已用完，请充值后再试");
    }

    @Test
    void plainRateLimitKeepsGenericMessage() {
        Throwable error = new RuntimeException("429 - too many requests");

        assertThat(ChatServiceImpl.translateErrorMessage(error))
                .isEqualTo("问答请求失败，请稍后重试");
    }

    @Test
    void unrelatedErrorKeepsGenericMessage() {
        Throwable error = new RuntimeException("timeout");

        assertThat(ChatServiceImpl.translateErrorMessage(error))
                .isEqualTo("问答请求失败，请稍后重试");
    }
}
