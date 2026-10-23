package com.wisread.service.impl;

import com.wisread.dto.KnowledgeRetrievalTestRequest;
import com.wisread.dto.KnowledgeRetrievalTestResponse;
import com.wisread.dto.SourceResponse;
import com.wisread.entity.KnowledgeBase;
import com.wisread.exception.ApiException;
import com.wisread.model.ChunkSearchResult;
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
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库检索测试端点（POST /retrieval-test）服务层单元测试（Mockito，不启动 Spring）：
 * 验证召回走 Agentic RAG 编排器（agent 关闭时透传单轮结果）、无关问题拒答且不调答案生成、
 * 各阶段耗时字段存在、越权 403 与两个知识库同问题互不影响。
 * 阈值过滤/rerankK 截断的检索口径行为已下沉到编排器 + KnowledgeRetrievalPipeline
 * （见 KnowledgeRagOrchestratorTest / CandidateMergeTest），本测试聚焦端点层接线与下游行为。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalTestServiceTest {

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

    private KnowledgeBase kb(Long id, Integer topK, Double threshold, Boolean rerankEnabled, Integer rerankK) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(id);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setTopK(topK);
        knowledgeBase.setSimilarityThreshold(threshold);
        knowledgeBase.setRerankEnabled(rerankEnabled);
        knowledgeBase.setRerankK(rerankK);
        knowledgeBase.setAgentEnabled(false);
        return knowledgeBase;
    }

    private ChunkSearchResult hit(long chunkId, double distance) {
        return new ChunkSearchResult(chunkId, 601L, "kb文档.pdf", "知识库片段" + chunkId, 2, 3, "页", distance);
    }

    // 编排器透传单轮结果（agent 关闭时与现状一致的形状）
    private RetrievalOutcome singleRoundOutcome(List<ChunkSearchResult> candidates) {
        return new RetrievalOutcome(candidates, 1, null, null, List.of());
    }

    private void stubRewritePassthrough() {
        when(queryRewriteService.rewrite(eq(QUESTION), anyList(), eq(USER_ID))).thenReturn(QUESTION);
    }

    private void stubEmbedding() {
        when(embeddingService.embed(anyList(), eq(USER_ID))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
    }

    private void stubAnswer(String answer) {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
        when(citationParsingService.parseAndValidate(eq(answer), anyList())).thenReturn(List.of(
                new SourceResponse(1, 1L, 601L, "kb文档.pdf", 2, 3, "页", "知识库片段1")));
    }

    // ===== 正常链路 =====

    @Test
    void testReturnsAllStagesAndUsedConfigMatchesRecall() {
        // usedConfig 快照与库配置一致；recall 为编排器最终候选（透传）
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenReturn(kb(KB_ID, 5, 0.8, true, 2));
        stubRewritePassthrough();
        stubEmbedding();
        stubAnswer("依据 [1] 的回答");
        when(knowledgeRagOrchestrator.orchestrate(eq(QUESTION), any(float[].class),
                any(KnowledgeBase.class), eq(USER_ID)))
                .thenReturn(singleRoundOutcome(List.of(hit(1L, 0.1), hit(2L, 0.15))));

        KnowledgeRetrievalTestResponse response =
                testService.test(USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION));

        // rewrite 阶段：无历史 → passthrough 原问题
        assertThat(response.rewrite().originalQuestion()).isEqualTo(QUESTION);
        assertThat(response.rewrite().rewrittenQuestion()).isEqualTo(QUESTION);
        assertThat(response.rewrite().rewritten()).isFalse();
        assertThat(response.rewrite().mode()).isEqualTo("passthrough");

        // usedConfig 快照：与库配置严格一致（过滤口径行为已下沉编排器）
        assertThat(response.usedConfig().topK()).isEqualTo(5);
        assertThat(response.usedConfig().similarityThreshold()).isEqualTo(0.8);
        assertThat(response.usedConfig().rerankEnabled()).isTrue();
        assertThat(response.usedConfig().rerankK()).isEqualTo(2);
        assertThat(response.recall()).extracting(KnowledgeRetrievalTestResponse.RecallItem::chunkId)
                .containsExactly(1L, 2L);
        assertThat(response.recall().get(0).similarity()).isEqualTo(1 - 0.1);
        assertThat(response.recall().get(0).filename()).isEqualTo("kb文档.pdf");
        assertThat(response.recall().get(0).snippet()).isNotBlank();

        // rerank 阶段：占位标注
        assertThat(response.rerank().enabled()).isTrue();
        assertThat(response.rerank().strategy()).isEqualTo("placeholder");
        assertThat(response.rerank().note()).contains("占位");

        // answer 阶段：答案与引用
        assertThat(response.rejected()).isFalse();
        assertThat(response.answer()).isEqualTo("依据 [1] 的回答");
        assertThat(response.citations()).hasSize(1);

        // timings：四个阶段字段全部存在且非负
        assertThat(response.timings().rewriteMs()).isGreaterThanOrEqualTo(0);
        assertThat(response.timings().recallMs()).isGreaterThanOrEqualTo(0);
        assertThat(response.timings().rerankMs()).isGreaterThanOrEqualTo(0);
        assertThat(response.timings().answerMs()).isGreaterThanOrEqualTo(0);
    }

    // ===== 拒答 =====

    @Test
    void testRejectsWithoutHitsAndSkipsAnswerGeneration() {
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenReturn(kb(KB_ID, 5, 0.8, true, 2));
        stubRewritePassthrough();
        stubEmbedding();
        when(knowledgeRagOrchestrator.orchestrate(eq(QUESTION), any(float[].class),
                any(KnowledgeBase.class), eq(USER_ID)))
                .thenReturn(singleRoundOutcome(List.of()));

        KnowledgeRetrievalTestResponse response =
                testService.test(USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION));

        assertThat(response.rejected()).isTrue();
        assertThat(response.answer()).isEqualTo("未在知识库中找到与问题相关的内容");
        assertThat(response.recall()).isEmpty();
        assertThat(response.citations()).isEmpty();
        // 拒答时不调用答案生成
        verify(chatModel, never()).call(any(Prompt.class));
        verify(citationParsingService, never()).parseAndValidate(anyString(), anyList());
        assertThat(response.timings().answerMs()).isEqualTo(0L);
    }

    @Test
    void testRejectsWhenAllHitsBelowThreshold() {
        // 全部命中超阈值时编排器输出空候选 → 端点拒答（过滤口径行为在编排器内部，已由其专属测试覆盖）
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenReturn(kb(KB_ID, 5, 0.8, false, null));
        stubRewritePassthrough();
        stubEmbedding();
        when(knowledgeRagOrchestrator.orchestrate(eq(QUESTION), any(float[].class),
                any(KnowledgeBase.class), eq(USER_ID)))
                .thenReturn(singleRoundOutcome(List.of()));

        KnowledgeRetrievalTestResponse response =
                testService.test(USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION));

        assertThat(response.rejected()).isTrue();
        assertThat(response.answer()).isEqualTo("未在知识库中找到与问题相关的内容");
        assertThat(response.recall()).isEmpty();
        verify(chatModel, never()).call(any(Prompt.class));
    }

    // ===== 越权与参数校验 =====

    @Test
    void testRejectsForeignKnowledgeBaseWith403() {
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "无权访问该知识库"));

        assertThatThrownBy(() -> testService.test(USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION)))
                .isInstanceOf(ApiException.class)
                .hasMessage("无权访问该知识库")
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);

        // 越权时不做任何检索与生成
        verify(embeddingService, never()).embed(anyList(), any());
        verify(knowledgeRagOrchestrator, never()).orchestrate(anyString(), any(float[].class), any(), any());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void testRejectsBlankQuestion() {
        assertThatThrownBy(() -> testService.test(USER_ID, KB_ID, new KnowledgeRetrievalTestRequest("   ")))
                .isInstanceOf(ApiException.class)
                .hasMessage("问题不能为空")
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(knowledgeBaseService, never()).findOwnedKnowledgeBase(any(), any());
    }

    // ===== 库间隔离 =====

    @Test
    void testSameQuestionOnTwoKnowledgeBasesDoNotInterfere() {
        // 库 A：topK=3、阈值 0.5、关闭 rerank；库 B：topK=20、阈值 0.9、关闭 rerank
        KnowledgeBase kbA = kb(KB_ID, 3, 0.5, false, null);
        KnowledgeBase kbB = kb(100L, 20, 0.9, false, null);
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID)).thenReturn(kbA);
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, 100L)).thenReturn(kbB);
        stubRewritePassthrough();
        stubEmbedding();
        stubAnswer("依据 [1] 的回答");
        // 编排器 mock 按传入知识库区分：各自返回各自的候选，互不可见
        when(knowledgeRagOrchestrator.orchestrate(eq(QUESTION), any(float[].class), eq(kbA), eq(USER_ID)))
                .thenReturn(singleRoundOutcome(List.of(hit(11L, 0.3))));
        when(knowledgeRagOrchestrator.orchestrate(eq(QUESTION), any(float[].class), eq(kbB), eq(USER_ID)))
                .thenReturn(singleRoundOutcome(List.of(hit(21L, 0.05))));

        KnowledgeRetrievalTestResponse responseA = testService.test(
                USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION));
        KnowledgeRetrievalTestResponse responseB = testService.test(
                USER_ID, 100L, new KnowledgeRetrievalTestRequest(QUESTION));

        // 库 A 与库 B 的召回互不影响
        assertThat(responseA.usedConfig().topK()).isEqualTo(3);
        assertThat(responseA.usedConfig().similarityThreshold()).isEqualTo(0.5);
        assertThat(responseA.recall()).extracting(KnowledgeRetrievalTestResponse.RecallItem::chunkId)
                .containsExactly(11L);
        assertThat(responseB.usedConfig().topK()).isEqualTo(20);
        assertThat(responseB.usedConfig().similarityThreshold()).isEqualTo(0.9);
        assertThat(responseB.recall()).extracting(KnowledgeRetrievalTestResponse.RecallItem::chunkId)
                .containsExactly(21L);
    }

    // ===== 配置快照与生效值一致性 =====

    @Test
    void testUsedConfigReflectsFallbackTopKWhenUnconfigured() {
        // 库内 topK 未配置（null）→ usedConfig 记录回退默认值 10，编排器正常被调用
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenReturn(kb(KB_ID, null, 0.65, false, null));
        stubRewritePassthrough();
        stubEmbedding();
        stubAnswer("依据 [1] 的回答");
        when(knowledgeRagOrchestrator.orchestrate(eq(QUESTION), any(float[].class),
                any(KnowledgeBase.class), eq(USER_ID)))
                .thenReturn(singleRoundOutcome(List.of(hit(1L, 0.1))));

        KnowledgeRetrievalTestResponse response =
                testService.test(USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION));

        assertThat(response.usedConfig().topK()).isEqualTo(10);
        verify(knowledgeRagOrchestrator).orchestrate(
                eq(QUESTION), any(float[].class), any(KnowledgeBase.class), eq(USER_ID));
    }
}
