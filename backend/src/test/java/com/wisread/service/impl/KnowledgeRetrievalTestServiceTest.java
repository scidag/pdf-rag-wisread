package com.wisread.service.impl;

import com.wisread.dto.KnowledgeRetrievalTestRequest;
import com.wisread.dto.KnowledgeRetrievalTestResponse;
import com.wisread.dto.SourceResponse;
import com.wisread.entity.KnowledgeBase;
import com.wisread.exception.ApiException;
import com.wisread.model.KnowledgeChunkSearchResult;
import com.wisread.service.CitationParsingService;
import com.wisread.service.EmbeddingService;
import com.wisread.service.KnowledgeBaseService;
import com.wisread.service.KnowledgeVectorIndexingService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库检索测试端点（POST /retrieval-test）服务层单元测试（Mockito，不启动 Spring）：
 * 验证 usedConfig 与实际召回一致（topK/阈值/截断）、无关问题拒答且不调答案生成、
 * 各阶段耗时字段存在、越权 403 与两个知识库同问题互不影响。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalTestServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private KnowledgeVectorIndexingService knowledgeVectorIndexingService;

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
                knowledgeVectorIndexingService,
                embeddingService,
                queryRewriteService,
                citationParsingService,
                chatModel
        );
    }

    private KnowledgeBase kb(Long id, Integer topK, Double threshold, Boolean rerankEnabled, Integer rerankK) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(id);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setTopK(topK);
        knowledgeBase.setSimilarityThreshold(threshold);
        knowledgeBase.setRerankEnabled(rerankEnabled);
        knowledgeBase.setRerankK(rerankK);
        return knowledgeBase;
    }

    private KnowledgeChunkSearchResult hit(long chunkId, double distance) {
        return new KnowledgeChunkSearchResult(
                chunkId, 601L, "kb文档.pdf", "知识库片段" + chunkId, 2, 3, "页", distance);
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
        // topK=5 召回上限；threshold=0.8 → 距离阈值 0.2 过滤掉 0.3；rerankK=2 再截断到前 2 条
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenReturn(kb(KB_ID, 5, 0.8, true, 2));
        stubRewritePassthrough();
        stubEmbedding();
        stubAnswer("依据 [1] 的回答");
        when(knowledgeVectorIndexingService.searchKnowledgeChunks(eq(USER_ID), eq(KB_ID), any(), eq(5)))
                .thenReturn(List.of(hit(1L, 0.1), hit(2L, 0.15), hit(3L, 0.18), hit(4L, 0.3)));

        KnowledgeRetrievalTestResponse response =
                testService.test(USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION));

        // rewrite 阶段：无历史 → passthrough 原问题
        assertThat(response.rewrite().originalQuestion()).isEqualTo(QUESTION);
        assertThat(response.rewrite().rewrittenQuestion()).isEqualTo(QUESTION);
        assertThat(response.rewrite().rewritten()).isFalse();
        assertThat(response.rewrite().mode()).isEqualTo("passthrough");

        // recall 阶段：阈值过滤 + rerankK 截断后只保留前 2 条（与 usedConfig 严格一致）
        assertThat(response.usedConfig().topK()).isEqualTo(5);
        assertThat(response.usedConfig().similarityThreshold()).isEqualTo(0.8);
        assertThat(response.usedConfig().rerankEnabled()).isTrue();
        assertThat(response.usedConfig().rerankK()).isEqualTo(2);
        assertThat(response.recall()).extracting(KnowledgeRetrievalTestResponse.RecallItem::chunkId)
                .containsExactly(1L, 2L);
        assertThat(response.recall()).allMatch(item -> item.distance() <= 0.2);
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
        assertThat(response.timings().rewriteMs()).isNotNull();
        assertThat(response.timings().recallMs()).isNotNull();
        assertThat(response.timings().rerankMs()).isNotNull();
        assertThat(response.timings().answerMs()).isNotNull();
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
        when(knowledgeVectorIndexingService.searchKnowledgeChunks(eq(USER_ID), eq(KB_ID), any(), eq(5)))
                .thenReturn(List.of());

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
        // threshold=0.8（距离阈值 0.2），全部命中 0.5/0.9 超阈值 → 拒答
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenReturn(kb(KB_ID, 5, 0.8, false, null));
        stubRewritePassthrough();
        stubEmbedding();
        when(knowledgeVectorIndexingService.searchKnowledgeChunks(eq(USER_ID), eq(KB_ID), any(), eq(5)))
                .thenReturn(List.of(hit(1L, 0.5), hit(2L, 0.9)));

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
        verify(knowledgeVectorIndexingService, never())
                .searchKnowledgeChunks(any(), any(), any(), anyInt());
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
        // 库 A：topK=3、阈值 0.5（距离阈值 0.5）、关闭 rerank
        KnowledgeBase kbA = kb(KB_ID, 3, 0.5, false, null);
        // 库 B：topK=20、阈值 0.9（距离阈值 0.1）、关闭 rerank
        KnowledgeBase kbB = kb(100L, 20, 0.9, false, null);
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID)).thenReturn(kbA);
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, 100L)).thenReturn(kbB);
        stubRewritePassthrough();
        stubEmbedding();
        stubAnswer("依据 [1] 的回答");
        // 检索 mock 按 kbId 区分：各自返回各自的命中，互不可见
        when(knowledgeVectorIndexingService.searchKnowledgeChunks(eq(USER_ID), eq(KB_ID), any(), eq(3)))
                .thenReturn(List.of(hit(11L, 0.3), hit(12L, 0.8)));
        when(knowledgeVectorIndexingService.searchKnowledgeChunks(eq(USER_ID), eq(100L), any(), eq(20)))
                .thenReturn(List.of(hit(21L, 0.05)));

        KnowledgeRetrievalTestResponse responseA = testService.test(
                USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION));
        KnowledgeRetrievalTestResponse responseB = testService.test(
                USER_ID, 100L, new KnowledgeRetrievalTestRequest(QUESTION));

        // 库 A：召回上限 3、阈值过滤保留 0.3、丢弃 0.8
        assertThat(responseA.usedConfig().topK()).isEqualTo(3);
        assertThat(responseA.usedConfig().similarityThreshold()).isEqualTo(0.5);
        assertThat(responseA.recall()).extracting(KnowledgeRetrievalTestResponse.RecallItem::chunkId)
                .containsExactly(11L);
        // 库 B：召回上限 20、阈值 0.9 保留 0.05；与库 A 的命中互不影响
        assertThat(responseB.usedConfig().topK()).isEqualTo(20);
        assertThat(responseB.usedConfig().similarityThreshold()).isEqualTo(0.9);
        assertThat(responseB.recall()).extracting(KnowledgeRetrievalTestResponse.RecallItem::chunkId)
                .containsExactly(21L);
    }

    // ===== 配置快照与生效值一致性 =====

    @Test
    void testUsedConfigReflectsFallbackTopKWhenUnconfigured() {
        // 库内 topK 未配置（null）→ 使用回退默认值 10，usedConfig 如实记录
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenReturn(kb(KB_ID, null, 0.65, false, null));
        stubRewritePassthrough();
        stubEmbedding();
        stubAnswer("依据 [1] 的回答");
        when(knowledgeVectorIndexingService.searchKnowledgeChunks(eq(USER_ID), eq(KB_ID), any(), eq(10)))
                .thenReturn(List.of(hit(1L, 0.1)));

        KnowledgeRetrievalTestResponse response =
                testService.test(USER_ID, KB_ID, new KnowledgeRetrievalTestRequest(QUESTION));

        assertThat(response.usedConfig().topK()).isEqualTo(10);
        verify(knowledgeVectorIndexingService).searchKnowledgeChunks(
                eq(USER_ID), eq(KB_ID), any(), eq(10));
    }
}
