package com.wisread.service.impl;

import com.wisread.dto.ChatRequest;
import com.wisread.dto.SourceResponse;
import com.wisread.entity.AnswerSource;
import com.wisread.entity.Conversation;
import com.wisread.entity.KnowledgeBase;
import com.wisread.entity.Message;
import com.wisread.entity.Project;
import com.wisread.exception.ApiException;
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
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Chat 双链路单元测试（Mockito，不启动 Spring）：
 * 验证会话绑定独立知识库时走知识库检索链路（Agentic RAG 编排器唯一入口，agent 关闭时透传单轮结果），
 * 未绑定时保持原有项目检索链路不被调用改动。
 * 阈值过滤/rerank 截断的检索口径行为已下沉到编排器 + KnowledgeRetrievalPipeline
 * （见 KnowledgeRagOrchestratorTest / CandidateMergeTest），本测试聚焦 Chat 层接线与下游行为。
 * executor 以同步方式执行（Runnable::run），使异步问答流程在测试线程内完成。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceImplKnowledgeBaseTest {

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
    private ChatMemory chatMemory;

    @Mock
    private UserMemoryService userMemoryService;

    @Mock
    private UserMemoryExtractionService userMemoryExtractionService;

    private ChatServiceImpl chatService;

    private static final Long USER_ID = 1L;
    private static final Long PROJECT_ID = 10L;
    private static final Long CONVERSATION_ID = 5L;
    private static final Long KNOWLEDGE_BASE_ID = 99L;
    private static final AtomicLong MESSAGE_IDS = new AtomicLong(100);

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

    // ===== 测试数据构造 =====

    private Conversation conversation(Long knowledgeBaseId) {
        Conversation conversation = new Conversation();
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        conversation.setUserId(USER_ID);
        conversation.setProjectId(PROJECT_ID);
        conversation.setKnowledgeBaseId(knowledgeBaseId);
        return conversation;
    }

    private KnowledgeBase knowledgeBase(String status, Integer topK, Double threshold,
                                        Boolean rerankEnabled, Integer rerankK) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        ReflectionTestUtils.setField(knowledgeBase, "id", KNOWLEDGE_BASE_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setStatus(status);
        knowledgeBase.setTopK(topK);
        knowledgeBase.setSimilarityThreshold(threshold);
        knowledgeBase.setRerankEnabled(rerankEnabled);
        knowledgeBase.setRerankK(rerankK);
        knowledgeBase.setAgentEnabled(false);
        return knowledgeBase;
    }

    private ChunkSearchResult hit(long chunkId, long documentId, double distance) {
        return new ChunkSearchResult(chunkId, documentId, "kb文档.pdf", "知识库片段" + chunkId, 2, 3, "页", distance);
    }

    // 编排器透传单轮结果（agent 关闭时与现状一致的形状）
    private RetrievalOutcome singleRoundOutcome(List<ChunkSearchResult> candidates) {
        return new RetrievalOutcome(candidates, 1, null, null, List.of());
    }

    private void stubOrchestrate(List<ChunkSearchResult> candidates) {
        when(knowledgeRagOrchestrator.orchestrate(eq("改写后的问题"), any(float[].class),
                any(KnowledgeBase.class), eq(USER_ID)))
                .thenReturn(singleRoundOutcome(candidates));
    }

    private void stubQuestionFlow(Conversation conversation) {
        when(conversationRepository.findByUserIdAndId(USER_ID, CONVERSATION_ID))
                .thenReturn(Optional.of(conversation));
        Project project = new Project();
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, PROJECT_ID))
                .thenReturn(Optional.of(project));
        when(messageRepository.findTop10ByConversationIdOrderByCreatedAtDesc(CONVERSATION_ID))
                .thenReturn(List.of());
        when(messageRepository.insert(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            ReflectionTestUtils.setField(message, "id", MESSAGE_IDS.incrementAndGet());
            return 1;
        });
        when(queryRewriteService.rewrite(eq("问题"), anyList(), eq(USER_ID))).thenReturn("改写后的问题");
        when(embeddingService.embed(anyList(), eq(USER_ID))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
    }

    private void stubAnswerStream(String answer, List<SourceResponse> sources) {
        when(chatMemory.get(String.valueOf(CONVERSATION_ID))).thenReturn(List.of());
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(answer))))));
        when(citationParsingService.parseAndValidate(eq(answer), anyList())).thenReturn(sources);
        when(tokenCounter.count(anyString())).thenReturn(1);
    }

    private void ask() {
        chatService.ask(USER_ID, CONVERSATION_ID, new ChatRequest("问题"));
    }

    // ===== Chat 双链路（绑库分支接入编排器后的接线验证）=====

    @Test
    void askWithKnowledgeBaseUsesKnowledgeRetrievalAndPersistsAnswerSource() {
        Conversation conversation = conversation(KNOWLEDGE_BASE_ID);
        stubQuestionFlow(conversation);
        stubAnswerStream("依据 [1] 的回答", List.of(new SourceResponse(
                1, 501L, 601L, "kb文档.pdf", 2, 3, "页", "知识库片段501")));
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(knowledgeBase(KnowledgeBase.STATUS_ACTIVE, 5, 0.8, true, 3));
        stubOrchestrate(List.of(hit(501L, 601L, 0.1)));

        ask();

        // 绑库会话：唯一检索入口是编排器，项目检索与通用 rerank 均不被调用
        verify(vectorIndexingService, never()).searchWithContent(any(), any(), any(), anyInt());
        verify(rerankService, never()).rerank(anyString(), anyList());
        verify(knowledgeRagOrchestrator).orchestrate(
                eq("改写后的问题"), any(float[].class), any(KnowledgeBase.class), eq(USER_ID));

        // 引用落库复用 AnswerSource：chunkId/documentId 为 knowledge_chunks.id / knowledge_documents.id
        ArgumentCaptor<AnswerSource> sourceCaptor = ArgumentCaptor.forClass(AnswerSource.class);
        verify(answerSourceRepository).insert(sourceCaptor.capture());
        assertThat(sourceCaptor.getValue().getChunkId()).isEqualTo(501L);
        assertThat(sourceCaptor.getValue().getDocumentId()).isEqualTo(601L);
    }

    @Test
    void askWithoutKnowledgeBaseKeepsProjectRetrieval() {
        Conversation conversation = conversation(null);
        stubQuestionFlow(conversation);
        stubAnswerStream("依据 [1] 的回答", List.of(new SourceResponse(
                1, 1L, 2L, "项目文档.pdf", 1, 1, "页", "项目片段")));
        List<ChunkSearchResult> projectHits = List.of(
                new ChunkSearchResult(1L, 2L, "项目文档.pdf", "项目片段", 1, 1, 0.3));
        when(vectorIndexingService.searchWithContent(
                eq(USER_ID), eq(PROJECT_ID), any(), eq(10))).thenReturn(projectHits);
        when(rerankService.rerank(eq("改写后的问题"), eq(projectHits))).thenReturn(projectHits);

        ask();

        // 回归验证：未绑库走项目检索 + rerank，知识库编排链路完全不被触碰
        verify(vectorIndexingService).searchWithContent(eq(USER_ID), eq(PROJECT_ID), any(), eq(10));
        verify(knowledgeBaseService, never()).findOwnedKnowledgeBase(any(), any());
        verify(knowledgeRagOrchestrator, never()).orchestrate(anyString(), any(float[].class), any(), anyLong());

        ArgumentCaptor<AnswerSource> sourceCaptor = ArgumentCaptor.forClass(AnswerSource.class);
        verify(answerSourceRepository).insert(sourceCaptor.capture());
        assertThat(sourceCaptor.getValue().getChunkId()).isEqualTo(1L);
        assertThat(sourceCaptor.getValue().getDocumentId()).isEqualTo(2L);
    }

    @Test
    void knowledgeBaseThresholdFiltersDistantChunks() {
        // 过滤口径行为已下沉到编排器 + 管线（KnowledgeRagOrchestratorTest 覆盖）；
        // 此处验证 Chat 层把编排器输出的候选原样传给引用解析
        Conversation conversation = conversation(KNOWLEDGE_BASE_ID);
        stubQuestionFlow(conversation);
        stubAnswerStream("回答", List.of());
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(knowledgeBase(KnowledgeBase.STATUS_ACTIVE, 10, 0.8, false, null));
        // 模拟编排器输出：阈值 0.2 内保留 0.1/0.05，0.25/0.9 已被过滤
        stubOrchestrate(List.of(hit(501L, 601L, 0.1), hit(503L, 602L, 0.05)));

        ask();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChunkSearchResult>> chunksCaptor =
                ArgumentCaptor.forClass((Class<List<ChunkSearchResult>>) (Class<?>) List.class);
        verify(citationParsingService).parseAndValidate(anyString(), chunksCaptor.capture());
        assertThat(chunksCaptor.getValue()).extracting(ChunkSearchResult::chunkId)
                .containsExactly(501L, 503L);
        assertThat(chunksCaptor.getValue()).extracting(ChunkSearchResult::distance)
                .allMatch(distance -> distance <= 0.2);
    }

    @Test
    void knowledgeBaseRerankKTruncatesCandidates() {
        // 截断口径行为已下沉到编排器 + 管线（KnowledgeRagOrchestratorTest 覆盖）；
        // 此处验证 Chat 层把编排器输出的截断结果原样传给引用解析
        Conversation conversation = conversation(KNOWLEDGE_BASE_ID);
        stubQuestionFlow(conversation);
        stubAnswerStream("回答", List.of());
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(knowledgeBase(KnowledgeBase.STATUS_ACTIVE, 10, 0.5, true, 2));
        // 模拟编排器输出：rerankK=2 只保留前 2 条
        stubOrchestrate(List.of(hit(501L, 601L, 0.1), hit(502L, 601L, 0.2)));

        ask();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChunkSearchResult>> chunksCaptor =
                ArgumentCaptor.forClass((Class<List<ChunkSearchResult>>) (Class<?>) List.class);
        verify(citationParsingService).parseAndValidate(anyString(), chunksCaptor.capture());
        assertThat(chunksCaptor.getValue()).extracting(ChunkSearchResult::chunkId)
                .containsExactly(501L, 502L);
    }

    @Test
    void askRejectsForeignKnowledgeBaseBeforeRetrieval() {
        Conversation conversation = conversation(KNOWLEDGE_BASE_ID);
        stubQuestionFlow(conversation);
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KNOWLEDGE_BASE_ID))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "无权访问该知识库"));

        ask();

        // 越权时归属校验先行拦截：编排器与项目检索均不执行
        verify(knowledgeRagOrchestrator, never()).orchestrate(anyString(), any(float[].class), any(), anyLong());
        verify(vectorIndexingService, never()).searchWithContent(any(), any(), any(), anyInt());
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    @Test
    void archivedKnowledgeBaseStillAnswerable() {
        Conversation conversation = conversation(KNOWLEDGE_BASE_ID);
        stubQuestionFlow(conversation);
        stubAnswerStream("回答", List.of(new SourceResponse(
                1, 501L, 601L, "kb文档.pdf", 2, 3, "页", "知识库片段501")));
        // ARCHIVED 归档库放行：既有绑定会话可继续问答
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(knowledgeBase(KnowledgeBase.STATUS_ARCHIVED, 5, 0.8, false, null));
        stubOrchestrate(List.of(hit(501L, 601L, 0.1)));

        ask();

        verify(knowledgeRagOrchestrator).orchestrate(
                eq("改写后的问题"), any(float[].class), any(KnowledgeBase.class), eq(USER_ID));
        ArgumentCaptor<AnswerSource> sourceCaptor = ArgumentCaptor.forClass(AnswerSource.class);
        verify(answerSourceRepository).insert(sourceCaptor.capture());
        assertThat(sourceCaptor.getValue().getChunkId()).isEqualTo(501L);
    }

    @Test
    void knowledgeBaseRetrievalRejectsWhenAllChunksBelowThreshold() {
        Conversation conversation = conversation(KNOWLEDGE_BASE_ID);
        stubQuestionFlow(conversation);
        // 编排器输出空候选（阈值 0.2 内无命中）→ 触发拒答文案
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KNOWLEDGE_BASE_ID))
                .thenReturn(knowledgeBase(KnowledgeBase.STATUS_ACTIVE, 5, 0.8, false, null));
        stubOrchestrate(List.of());

        ask();

        verify(chatModel, never()).stream(any(Prompt.class));
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).insert(messageCaptor.capture());
        List<Message> persisted = messageCaptor.getAllValues();
        assertThat(persisted.get(0).getRole()).isEqualTo("user");
        assertThat(persisted.get(1).getRole()).isEqualTo("assistant");
        assertThat(persisted.get(1).getContent()).isEqualTo("文档中没有找到相关信息");
        verify(answerSourceRepository, never()).insert(any(AnswerSource.class));
    }

    @Test
    void projectRetrievalStillRejectsLowQualityMatches() {
        Conversation conversation = conversation(null);
        stubQuestionFlow(conversation);
        // 回归验证：未绑库固定 0.65 距离阈值拒答行为保持不变
        when(vectorIndexingService.searchWithContent(
                eq(USER_ID), eq(PROJECT_ID), any(), eq(10)))
                .thenReturn(List.of(new ChunkSearchResult(1L, 2L, "项目文档.pdf", "项目片段", 1, 1, 0.9)));
        when(rerankService.rerank(anyString(), anyList()))
                .thenReturn(List.of(new ChunkSearchResult(1L, 2L, "项目文档.pdf", "项目片段", 1, 1, 0.9)));

        ask();

        verify(chatModel, never()).stream(any(Prompt.class));
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, times(2)).insert(messageCaptor.capture());
        assertThat(messageCaptor.getAllValues().get(1).getContent())
                .isEqualTo("文档中没有找到相关信息");
    }
}
