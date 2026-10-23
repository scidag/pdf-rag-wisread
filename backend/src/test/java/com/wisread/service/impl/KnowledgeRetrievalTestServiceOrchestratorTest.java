package com.wisread.service.impl;

import com.wisread.dto.KnowledgeRetrievalTestRequest;
import com.wisread.dto.KnowledgeRetrievalTestResponse;
import com.wisread.entity.KnowledgeBase;
import com.wisread.model.ChunkSearchResult;
import com.wisread.model.RetrievalIteration;
import com.wisread.model.RetrievalOutcome;
import com.wisread.service.CitationParsingService;
import com.wisread.service.EmbeddingService;
import com.wisread.service.KnowledgeBaseService;
import com.wisread.service.KnowledgeRagOrchestrator;
import com.wisread.service.QueryRewriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索测试端点接入 Agentic RAG 编排器的单元测试（Mockito，不启动 Spring）：
 * 验证编排器 outcome 的 iterations 轮次明细与 agent 状态正确映射到响应、
 * 空候选拒答语义保持（不调用答案生成）、agent 关闭单轮结果正常透出。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalTestServiceOrchestratorTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private KnowledgeRagOrchestrator knowledgeRagOrchestrator;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private QueryRewriteService queryRewriteService;

    @Mock
    private CitationParsingService citationParsingService;

    @Mock
    private ChatModel chatModel;

    private KnowledgeRetrievalTestServiceImpl testService;

    private static final Long USER_ID = 1L;
    private static final Long KB_ID = 99L;
    private static final String QUESTION = "这个问题讲了什么";

    @BeforeEach
    void setUp() {
        testService = new KnowledgeRetrievalTestServiceImpl(
                knowledgeBaseService,
                knowledgeRagOrchestrator,
                embeddingService,
                queryRewriteService,
                citationParsingService,
                chatModel
        );
    }

    // ===== 测试数据构造 =====

    private KnowledgeBase kb() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(KB_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setTopK(10);
        knowledgeBase.setAgentEnabled(false);
        return knowledgeBase;
    }

    private ChunkSearchResult hit(long chunkId, double distance) {
        return new ChunkSearchResult(chunkId, 601L, "kb文档.pdf", "知识库片段" + chunkId, 2, 3, "页", distance);
    }

    private void stubQuestionFlow() {
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID)).thenReturn(kb());
        when(queryRewriteService.rewrite(eq(QUESTION), anyList(), eq(USER_ID))).thenReturn(QUESTION);
        when(embeddingService.embed(anyList(), eq(USER_ID))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
    }

    // ===== 编排器结果映射 =====

    @Test
    void mapsOrchestratorOutcomeToResponse() {
        // 2 轮 outcome：iterations 轮次明细与 agent 状态透出，recall 为最终候选
        stubQuestionFlow();
        List<ChunkSearchResult> finalCandidates = List.of(hit(501L, 0.1), hit(502L, 0.2));
        List<RetrievalIteration> iterations = List.of(
                new RetrievalIteration(1, QUESTION, 2, false, "LOW_HITS", 50L),
                new RetrievalIteration(2, "改写后的问题", 2, true, "SUFFICIENT", 30L));
        when(knowledgeRagOrchestrator.orchestrate(eq(QUESTION), any(float[].class),
                any(KnowledgeBase.class), eq(USER_ID)))
                .thenReturn(new RetrievalOutcome(finalCandidates, 2, "COMPLETED", null, iterations));
        String answer = "依据 [1] 的回答";
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
        when(citationParsingService.parseAndValidate(eq(answer), anyList())).thenReturn(List.of());

        KnowledgeRetrievalTestResponse response =
                testService.test(USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION));

        assertThat(response.iterations()).hasSize(2);
        assertThat(response.iterations().get(0).round()).isEqualTo(1);
        assertThat(response.iterations().get(0).query()).isEqualTo(QUESTION);
        assertThat(response.iterations().get(0).hits()).isEqualTo(2);
        assertThat(response.iterations().get(0).sufficient()).isFalse();
        assertThat(response.iterations().get(0).reason()).isEqualTo("LOW_HITS");
        assertThat(response.iterations().get(1).round()).isEqualTo(2);
        assertThat(response.iterations().get(1).query()).isEqualTo("改写后的问题");
        assertThat(response.iterations().get(1).sufficient()).isTrue();
        assertThat(response.agent().retryStatus()).isEqualTo("COMPLETED");
        assertThat(response.agent().degradationReason()).isNull();
        // recall 为编排器最终候选（合并后），按距离升序
        assertThat(response.recall()).extracting(KnowledgeRetrievalTestResponse.RecallItem::chunkId)
                .containsExactly(501L, 502L);
        assertThat(response.rejected()).isFalse();
        assertThat(response.answer()).isEqualTo(answer);
    }

    // ===== 拒答语义保持 =====

    @Test
    void emptyCandidatesStillRejected() {
        // 编排器最终候选为空 → 拒答且不调用答案生成（与原空命中语义一致）
        stubQuestionFlow();
        when(knowledgeRagOrchestrator.orchestrate(eq(QUESTION), any(float[].class),
                any(KnowledgeBase.class), eq(USER_ID)))
                .thenReturn(new RetrievalOutcome(List.of(), 2, "COMPLETED", null, List.of()));

        KnowledgeRetrievalTestResponse response =
                testService.test(USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION));

        assertThat(response.rejected()).isTrue();
        assertThat(response.answer()).isEqualTo("未在知识库中找到与问题相关的内容");
        assertThat(response.recall()).isEmpty();
        assertThat(response.iterations()).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
        verify(citationParsingService, never()).parseAndValidate(any(), anyList());
        assertThat(response.timings().answerMs()).isEqualTo(0L);
    }

    // ===== agent 关闭单轮透出 =====

    @Test
    void agentOffStillReportsFirstRound() {
        // agent 关闭：单轮 outcome（iterations 仅 1 条、retryStatus=null）正常透出
        stubQuestionFlow();
        List<ChunkSearchResult> candidates = List.of(hit(501L, 0.1));
        when(knowledgeRagOrchestrator.orchestrate(eq(QUESTION), any(float[].class),
                any(KnowledgeBase.class), eq(USER_ID)))
                .thenReturn(new RetrievalOutcome(candidates, 1, null, null,
                        List.of(new RetrievalIteration(1, QUESTION, 1, true, "SUFFICIENT", 40L))));
        String answer = "依据 [1] 的回答";
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
        when(citationParsingService.parseAndValidate(eq(answer), anyList())).thenReturn(List.of());

        KnowledgeRetrievalTestResponse response =
                testService.test(USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION));

        assertThat(response.iterations()).hasSize(1);
        assertThat(response.iterations().get(0).round()).isEqualTo(1);
        assertThat(response.iterations().get(0).sufficient()).isTrue();
        assertThat(response.agent().retryStatus()).isNull();
        assertThat(response.agent().degradationReason()).isNull();
        assertThat(response.recall()).extracting(KnowledgeRetrievalTestResponse.RecallItem::chunkId)
                .containsExactly(501L);
    }
}
