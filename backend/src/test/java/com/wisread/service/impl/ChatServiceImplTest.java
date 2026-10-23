package com.wisread.service.impl;

import com.wisread.dto.ChatRequest;
import com.wisread.dto.SourceResponse;
import com.wisread.entity.Conversation;
import com.wisread.entity.KnowledgeBase;
import com.wisread.entity.Message;
import com.wisread.entity.Project;
import com.wisread.model.ChunkSearchResult;
import com.wisread.model.RetrievalOutcome;
import com.wisread.repository.AnswerSourceRepository;
import com.wisread.repository.ConversationRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.MessageRepository;
import com.wisread.repository.ProjectRepository;
import com.wisread.service.ChatLogService;
import com.wisread.service.CitationParsingService;
import com.wisread.service.EmbeddingService;
import com.wisread.service.KnowledgeBaseService;
import com.wisread.service.KnowledgeRagOrchestrator;
import com.wisread.service.QueryRewriteService;
import com.wisread.service.RerankService;
import com.wisread.service.TokenCounter;
import com.wisread.service.UsageLogService;
import com.wisread.service.UserMemoryExtractionService;
import com.wisread.service.UserMemoryService;
import com.wisread.service.VectorIndexingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Chat 绑库分支接入 Agentic RAG 编排器的接线单测（spec 4.4）：
 * 绑库分支检索调用替换为 orchestrator 唯一入口（未绑库零改动），
 * buildPrompt 引用编号文案随 chunks.size() 动态生成。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

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
    private EmbeddingService embeddingService;

    @Mock
    private VectorIndexingService vectorIndexingService;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private KnowledgeRagOrchestrator knowledgeRagOrchestrator;

    @Mock
    private RerankService rerankService;

    @Mock
    private QueryRewriteService queryRewriteService;

    @Mock
    private CitationParsingService citationParsingService;

    @Mock
    private TokenCounter tokenCounter;

    @Mock
    private UsageLogService usageLogService;

    @Mock
    private ChatLogService chatLogService;

    @Mock
    private ChatModel chatModel;

    @Mock
    private org.springframework.ai.chat.memory.ChatMemory chatMemory;

    @Mock
    private UserMemoryService userMemoryService;

    @Mock
    private UserMemoryExtractionService userMemoryExtractionService;

    private ChatServiceImpl chatService;

    private static final Long USER_ID = 1L;
    private static final Long PROJECT_ID = 10L;
    private static final Long CONVERSATION_ID = 5L;
    private static final Long KNOWLEDGE_BASE_ID = 99L;
    private static final float[] QUERY_EMBEDDING = {0.1f, 0.2f};

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(
                conversationRepository,
                projectRepository,
                documentRepository,
                messageRepository,
                answerSourceRepository,
                embeddingService,
                vectorIndexingService,
                knowledgeBaseService,
                knowledgeRagOrchestrator,
                rerankService,
                queryRewriteService,
                citationParsingService,
                tokenCounter,
                usageLogService,
                chatLogService,
                "test-model",
                chatModel,
                chatMemory,
                userMemoryService,
                userMemoryExtractionService,
                10,          // topK
                3,           // injectTopK
                false,       // memoryEnabled 关闭，屏蔽长期记忆旁路
                8,           // maxConcurrent
                2000,        // maxHistoryTokens
                10000L,      // slowThresholdMs
                new SimpleMeterRegistry(),
                Runnable::run // executor 同步执行
        );
    }

    private Conversation boundConversation() {
        Conversation conversation = new Conversation();
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setProjectId(PROJECT_ID);
        conversation.setKnowledgeBaseId(KNOWLEDGE_BASE_ID);
        return conversation;
    }

    private KnowledgeBase knowledgeBase() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        ReflectionTestUtils.setField(knowledgeBase, "id", KNOWLEDGE_BASE_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setTopK(10);
        knowledgeBase.setAgentEnabled(false); // agent 关闭：编排器透传单轮结果（现状行为）
        return knowledgeBase;
    }

    private ChunkSearchResult hit(long chunkId) {
        return new ChunkSearchResult(chunkId, 601L, "kb文档.pdf", "知识库片段" + chunkId, 2, 3, "页", 0.1);
    }

    private RetrievalOutcome passthroughOutcome(List<ChunkSearchResult> candidates) {
        return new RetrievalOutcome(candidates, 1, null, null, List.of());
    }

    private void stubFlow(Conversation conversation, RetrievalOutcome outcome) {
        when(conversationRepository.findByUserIdAndId(USER_ID, CONVERSATION_ID))
                .thenReturn(Optional.of(conversation));
        Project project = new Project();
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, PROJECT_ID))
                .thenReturn(Optional.of(project));
        when(messageRepository.findTop10ByConversationIdOrderByCreatedAtDesc(CONVERSATION_ID))
                .thenReturn(List.of());
        when(messageRepository.insert(any(Message.class))).thenReturn(1);
        when(queryRewriteService.rewrite(eq("问题"), anyList(), eq(USER_ID))).thenReturn("改写后的问题");
        when(embeddingService.embed(anyList(), eq(USER_ID))).thenReturn(List.of(QUERY_EMBEDDING));
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KNOWLEDGE_BASE_ID)).thenReturn(knowledgeBase());
        when(knowledgeRagOrchestrator.orchestrate(anyString(), any(float[].class), any(KnowledgeBase.class), eq(USER_ID)))
                .thenReturn(outcome);
        when(chatMemory.get(String.valueOf(CONVERSATION_ID))).thenReturn(List.of());
        String answer = "依据 [1] 的回答";
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(answer))))));
        when(citationParsingService.parseAndValidate(eq(answer), anyList())).thenReturn(List.of());
        when(tokenCounter.count(anyString())).thenReturn(1);
    }

    @Test
    void askWithKnowledgeBaseCallsOrchestratorWithRewrittenQueryAndEmbedding() {
        // 绑库分支唯一检索入口 = orchestrator.orchestrate(改写后问题, 调用方向量, kb, userId)（spec 4.2.1 输入不变式）
        stubFlow(boundConversation(), passthroughOutcome(List.of(hit(501L))));

        chatService.ask(USER_ID, CONVERSATION_ID, new ChatRequest("问题"));

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<float[]> embeddingCaptor = ArgumentCaptor.forClass(float[].class);
        verify(knowledgeRagOrchestrator).orchestrate(queryCaptor.capture(), embeddingCaptor.capture(),
                any(KnowledgeBase.class), eq(USER_ID));
        assertThat(queryCaptor.getValue()).isEqualTo("改写后的问题");
        assertThat(embeddingCaptor.getValue()).isSameAs(QUERY_EMBEDDING);
    }

    @Test
    void askWithoutKnowledgeBaseNeverCallsOrchestrator() {
        // 未绑库链路零改动：orchestrator 不被触碰，项目检索照旧
        Conversation conversation = new Conversation();
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setProjectId(PROJECT_ID);
        when(conversationRepository.findByUserIdAndId(USER_ID, CONVERSATION_ID))
                .thenReturn(Optional.of(conversation));
        Project project = new Project();
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, PROJECT_ID))
                .thenReturn(Optional.of(project));
        when(messageRepository.findTop10ByConversationIdOrderByCreatedAtDesc(CONVERSATION_ID))
                .thenReturn(List.of());
        when(messageRepository.insert(any(Message.class))).thenReturn(1);
        when(queryRewriteService.rewrite(eq("问题"), anyList(), eq(USER_ID))).thenReturn("改写后的问题");
        when(embeddingService.embed(anyList(), eq(USER_ID))).thenReturn(List.of(QUERY_EMBEDDING));
        List<ChunkSearchResult> projectHits = List.of(hit(1L));
        when(vectorIndexingService.searchWithContent(eq(USER_ID), eq(PROJECT_ID), any(), eq(10)))
                .thenReturn(projectHits);
        when(rerankService.rerank(eq("改写后的问题"), eq(projectHits))).thenReturn(projectHits);
        when(chatMemory.get(String.valueOf(CONVERSATION_ID))).thenReturn(List.of());
        String answer = "依据 [1] 的回答";
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(answer))))));
        when(citationParsingService.parseAndValidate(eq(answer), anyList())).thenReturn(List.of());
        when(tokenCounter.count(anyString())).thenReturn(1);

        chatService.ask(USER_ID, CONVERSATION_ID, new ChatRequest("问题"));

        verify(knowledgeRagOrchestrator, never()).orchestrate(anyString(), any(float[].class), any(), any());
        verify(knowledgeBaseService, never()).findOwnedKnowledgeBase(any(), any());
    }

    @Test
    void buildPromptCitationRangeIsDynamic() {
        // 引用编号文案 [1]..[N] 随实际片段数动态生成，消除 rerankK ≠ 3 时编号与片段数不一致的隐患
        stubFlow(boundConversation(), passthroughOutcome(List.of(hit(501L), hit(502L))));

        chatService.ask(USER_ID, CONVERSATION_ID, new ChatRequest("问题"));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(promptCaptor.capture());
        SystemMessage systemMessage = promptCaptor.getValue().getInstructions().stream()
                .filter(message -> message instanceof SystemMessage)
                .map(message -> (SystemMessage) message)
                .findFirst()
                .orElseThrow();
        assertThat(systemMessage.getText()).contains("[1]..[2]");
        assertThat(systemMessage.getText()).doesNotContain("[1]..[3]");
    }
}
