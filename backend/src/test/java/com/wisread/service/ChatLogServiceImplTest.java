package com.wisread.service;

import com.wisread.entity.ChatLog;
import com.wisread.model.ChunkSearchResult;
import com.wisread.repository.ChatLogRepository;
import com.wisread.service.impl.ChatLogServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatLogServiceImplTest {

    @Mock
    private ChatLogRepository chatLogRepository;

    private ChatLogServiceImpl chatLogService;

    @BeforeEach
    void setUp() {
        chatLogService = new ChatLogServiceImpl(chatLogRepository);
    }

    @Test
    void savesQuestionModelRetrievedContentAndDistinctDocumentNames() {
        chatLogService.log(1L, "问题", "qwen3.7-plus", List.of(
                new ChunkSearchResult(1L, 10L, "a.pdf", "alpha", 1, 2, 0.1),
                new ChunkSearchResult(2L, 10L, "a.pdf", "beta", 3, 3, 0.2),
                new ChunkSearchResult(3L, 11L, "b.pdf", "gamma", 1, 1, 0.3)
        ));

        ArgumentCaptor<ChatLog> captor = ArgumentCaptor.forClass(ChatLog.class);
        verify(chatLogRepository).insert(captor.capture());
        ChatLog saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getQuestion()).isEqualTo("问题");
        assertThat(saved.getModel()).isEqualTo("qwen3.7-plus");
        assertThat(saved.getRetrievedContent()).contains("alpha", "beta", "gamma");
        assertThat(saved.getDocumentNames()).isEqualTo("a.pdf, b.pdf");
    }
}
