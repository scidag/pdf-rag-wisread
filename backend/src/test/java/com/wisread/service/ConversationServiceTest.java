package com.wisread.service;

import com.wisread.dto.ConversationResponse;
import com.wisread.dto.CreateConversationRequest;
import com.wisread.dto.MessageResponse;
import com.wisread.dto.SourceResponse;
import com.wisread.entity.AnswerSource;
import com.wisread.entity.Conversation;
import com.wisread.entity.Document;
import com.wisread.entity.DocumentChunk;
import com.wisread.entity.KnowledgeBase;
import com.wisread.entity.Message;
import com.wisread.entity.Project;
import com.wisread.exception.ApiException;
import com.wisread.model.KnowledgeChunkSourceView;
import com.wisread.repository.AnswerSourceRepository;
import com.wisread.repository.ConversationRepository;
import com.wisread.repository.DocumentChunkRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.KnowledgeBaseRepository;
import com.wisread.repository.MessageRepository;
import com.wisread.repository.ProjectRepository;
import com.wisread.service.KnowledgeVectorIndexingService;
import com.wisread.service.impl.ConversationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Mock
    private KnowledgeVectorIndexingService knowledgeVectorIndexingService;

    private ConversationService conversationService;

    private static final Long USER_ID = 1L;
    private static final Long PROJECT_ID = 10L;
    private static final Long CONVERSATION_ID = 5L;
    private static final Long KNOWLEDGE_BASE_ID = 99L;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationServiceImpl(
                conversationRepository,
                projectRepository,
                documentRepository,
                messageRepository,
                answerSourceRepository,
                documentChunkRepository,
                knowledgeBaseRepository,
                knowledgeVectorIndexingService
        );
    }

    private void mockProjectOwned() {
        Project project = new Project();
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, PROJECT_ID))
                .thenReturn(Optional.of(project));
    }

    private KnowledgeBase knowledgeBase(String status) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        ReflectionTestUtils.setField(knowledgeBase, "id", KNOWLEDGE_BASE_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setStatus(status);
        return knowledgeBase;
    }

    private Conversation existingConversation(Long knowledgeBaseId) {
        Conversation conversation = new Conversation();
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setProjectId(PROJECT_ID);
        conversation.setKnowledgeBaseId(knowledgeBaseId);
        return conversation;
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

    // ===== 会话选库（SubTask 4.1）=====

    @Test
    void createBindsKnowledgeBaseWhenProvided() {
        mockProjectOwned();
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(knowledgeBase(KnowledgeBase.STATUS_ACTIVE)));
        when(conversationRepository.insert(any(Conversation.class))).thenReturn(1);

        ConversationResponse response = conversationService.create(
                USER_ID,
                new CreateConversationRequest(PROJECT_ID, "测试", KNOWLEDGE_BASE_ID)
        );

        assertThat(response.knowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).insert(captor.capture());
        assertThat(captor.getValue().getKnowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
    }

    @Test
    void createWithoutKnowledgeBaseKeepsLegacyBehavior() {
        mockProjectOwned();
        when(conversationRepository.insert(any(Conversation.class))).thenReturn(1);

        ConversationResponse response = conversationService.create(
                USER_ID,
                new CreateConversationRequest(PROJECT_ID, null)
        );

        assertThat(response.knowledgeBaseId()).isNull();
        verify(knowledgeBaseRepository, never()).findByUserIdAndIdAndDeletedAtIsNull(any(), any());
        verify(knowledgeBaseRepository, never()).findByUserIdAndIdAndDeletedAtIsNotNull(any(), any());
    }

    @Test
    void createRejectsForeignKnowledgeBase() {
        mockProjectOwned();
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.empty());
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNotNull(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.create(
                USER_ID,
                new CreateConversationRequest(PROJECT_ID, "测试", KNOWLEDGE_BASE_ID)
        ))
                .isInstanceOf(ApiException.class)
                .hasMessage("无权访问该知识库")
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createRejectsDeletedKnowledgeBase() {
        mockProjectOwned();
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.empty());
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNotNull(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(knowledgeBase(KnowledgeBase.STATUS_DRAFT)));

        assertThatThrownBy(() -> conversationService.create(
                USER_ID,
                new CreateConversationRequest(PROJECT_ID, "测试", KNOWLEDGE_BASE_ID)
        ))
                .isInstanceOf(ApiException.class)
                .hasMessage("知识库不存在或已删除")
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void patchBindsKnowledgeBase() {
        mockProjectOwned();
        when(conversationRepository.findByUserIdAndId(USER_ID, CONVERSATION_ID))
                .thenReturn(Optional.of(existingConversation(null)));
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(knowledgeBase(KnowledgeBase.STATUS_ACTIVE)));

        ConversationResponse response = conversationService.updateKnowledgeBase(
                USER_ID, CONVERSATION_ID, KNOWLEDGE_BASE_ID);

        assertThat(response.knowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
        verify(conversationRepository).update(isNull(), any());
    }

    @Test
    void patchAcceptsArchivedKnowledgeBase() {
        mockProjectOwned();
        when(conversationRepository.findByUserIdAndId(USER_ID, CONVERSATION_ID))
                .thenReturn(Optional.of(existingConversation(null)));
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(knowledgeBase(KnowledgeBase.STATUS_ARCHIVED)));

        ConversationResponse response = conversationService.updateKnowledgeBase(
                USER_ID, CONVERSATION_ID, KNOWLEDGE_BASE_ID);

        assertThat(response.knowledgeBaseId()).isEqualTo(KNOWLEDGE_BASE_ID);
    }

    @Test
    void patchUnbindsKnowledgeBaseWithNull() {
        mockProjectOwned();
        when(conversationRepository.findByUserIdAndId(USER_ID, CONVERSATION_ID))
                .thenReturn(Optional.of(existingConversation(KNOWLEDGE_BASE_ID)));

        ConversationResponse response = conversationService.updateKnowledgeBase(
                USER_ID, CONVERSATION_ID, null);

        assertThat(response.knowledgeBaseId()).isNull();
        // 解绑必须走显式置 NULL 的条件 UPDATE（updateById 会忽略 null 字段）
        verify(conversationRepository).update(isNull(), any());
        verify(knowledgeBaseRepository, never()).findByUserIdAndIdAndDeletedAtIsNull(any(), any());
    }

    @Test
    void patchRejectsForeignKnowledgeBase() {
        mockProjectOwned();
        when(conversationRepository.findByUserIdAndId(USER_ID, CONVERSATION_ID))
                .thenReturn(Optional.of(existingConversation(null)));
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.empty());
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNotNull(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.updateKnowledgeBase(
                USER_ID, CONVERSATION_ID, KNOWLEDGE_BASE_ID))
                .isInstanceOf(ApiException.class)
                .hasMessage("无权访问该知识库")
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(conversationRepository, never()).update(any(), any());
    }

    @Test
    void patchRejectsDeletedKnowledgeBase() {
        mockProjectOwned();
        when(conversationRepository.findByUserIdAndId(USER_ID, CONVERSATION_ID))
                .thenReturn(Optional.of(existingConversation(null)));
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.empty());
        when(knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNotNull(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(Optional.of(knowledgeBase(KnowledgeBase.STATUS_DRAFT)));

        assertThatThrownBy(() -> conversationService.updateKnowledgeBase(
                USER_ID, CONVERSATION_ID, KNOWLEDGE_BASE_ID))
                .isInstanceOf(ApiException.class)
                .hasMessage("知识库不存在或已删除")
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void patchRejectsForeignConversation() {
        when(conversationRepository.findByUserIdAndId(USER_ID, CONVERSATION_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.updateKnowledgeBase(
                USER_ID, CONVERSATION_ID, KNOWLEDGE_BASE_ID))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
