package com.wisread.service;

import com.wisread.dto.ConversationResponse;
import com.wisread.dto.CreateConversationRequest;
import com.wisread.entity.Conversation;
import com.wisread.entity.Project;
import com.wisread.exception.ApiException;
import com.wisread.repository.AnswerSourceRepository;
import com.wisread.repository.ConversationRepository;
import com.wisread.repository.DocumentChunkRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.MessageRepository;
import com.wisread.repository.ProjectRepository;
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
    private ProjectRepository projectRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private AnswerSourceRepository answerSourceRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    private ConversationService conversationService;

    private static final Long USER_ID = 1L;
    private static final Long PROJECT_ID = 10L;
    private static final Long CONVERSATION_ID = 5L;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(
                conversationRepository,
                projectRepository,
                documentRepository,
                messageRepository,
                answerSourceRepository,
                documentChunkRepository
        );
    }

    private void mockProjectOwned() {
        Project project = new Project();
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, PROJECT_ID))
                .thenReturn(Optional.of(project));
    }

    @Test
    void createRejectsWhenProjectNotFound() {
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, PROJECT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.create(
                USER_ID,
                new CreateConversationRequest(PROJECT_ID, "测试")
        )).isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createUsesDefaultTitleWhenBlank() {
        mockProjectOwned();
        when(conversationRepository.insert(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation conversation = invocation.getArgument(0);
            ReflectionTestUtils.setField(conversation, "id", 5L);
            return 1;
        });

        ConversationResponse response = conversationService.create(
                USER_ID,
                new CreateConversationRequest(PROJECT_ID, null)
        );

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.projectId()).isEqualTo(PROJECT_ID);
        assertThat(response.title()).isEqualTo("新会话");
    }

    @Test
    void createUsesProvidedTitle() {
        mockProjectOwned();
        when(conversationRepository.insert(any(Conversation.class))).thenAnswer(invocation -> {
            Conversation conversation = invocation.getArgument(0);
            ReflectionTestUtils.setField(conversation, "id", 6L);
            return 1;
        });

        ConversationResponse response = conversationService.create(
                USER_ID,
                new CreateConversationRequest(PROJECT_ID, "向量检索讨论")
        );

        assertThat(response.id()).isEqualTo(6L);
        assertThat(response.title()).isEqualTo("向量检索讨论");
    }

    @Test
    void messagesRejectConversationInDeletedProject() {
        Conversation conversation = new Conversation();
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        conversation.setProjectId(PROJECT_ID);
        when(conversationRepository.findByUserIdAndId(USER_ID, CONVERSATION_ID))
                .thenReturn(Optional.of(conversation));
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, PROJECT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.messages(USER_ID, CONVERSATION_ID))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
