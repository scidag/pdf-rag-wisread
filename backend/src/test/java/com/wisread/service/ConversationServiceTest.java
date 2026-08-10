package com.wisread.service;

import com.wisread.dto.ConversationResponse;
import com.wisread.dto.CreateConversationRequest;
import com.wisread.entity.Conversation;
import com.wisread.entity.Document;
import com.wisread.exception.ApiException;
import com.wisread.repository.AnswerSourceRepository;
import com.wisread.repository.ConversationRepository;
import com.wisread.repository.DocumentChunkRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private AnswerSourceRepository answerSourceRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(
                conversationRepository,
                documentRepository,
                messageRepository,
                answerSourceRepository,
                documentChunkRepository
        );
    }

    @Test
    void createRejectsDocumentThatIsNotReady() {
        Document document = new Document();
        document.setStatus("PROCESSING");
        when(documentRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> conversationService.create(
                1L,
                new CreateConversationRequest(10L, "测试")
        )).isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createUsesFilenameAsDefaultTitle() {
        Document document = new Document();
        document.setStatus("READY");
        document.setFilename("contract.pdf");
        when(documentRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.of(document));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation conversation = invocation.getArgument(0);
            ReflectionTestUtils.setField(conversation, "id", 5L);
            return conversation;
        });

        ConversationResponse response = conversationService.create(
                1L,
                new CreateConversationRequest(10L, null)
        );

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.title()).isEqualTo("contract.pdf");
    }
}
