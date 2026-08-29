package com.wisread.service;

import com.wisread.entity.Message;
import com.wisread.service.impl.QueryRewriteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class QueryRewriteServiceTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private TokenCounter tokenCounter;

    @Mock
    private UsageLogService usageLogService;

    private QueryRewriteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new QueryRewriteServiceImpl(chatModel, tokenCounter, usageLogService, "qwen-test");
        ReflectionTestUtils.setField(service, "skipWithoutPronoun", true);
    }

    @Test
    void skipsRewriteWhenQuestionHasNoPronoun() {
        String question = "Spring是什么？";

        String result = service.rewrite(question, List.of(new Message()), 1L);

        assertThat(result).isEqualTo(question);
        verifyNoInteractions(chatModel);
    }
}
