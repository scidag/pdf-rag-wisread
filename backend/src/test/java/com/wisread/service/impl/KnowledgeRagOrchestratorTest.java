package com.wisread.service.impl;

import com.wisread.entity.KnowledgeBase;
import com.wisread.model.ChunkSearchResult;
import com.wisread.model.GradeResult;
import com.wisread.model.KnowledgeChunkSearchResult;
import com.wisread.model.RetrievalOutcome;
import com.wisread.model.RetryRewriteResult;
import com.wisread.repository.KnowledgeBaseRepository;
import com.wisread.service.EmbeddingService;
import com.wisread.service.KnowledgeRagOrchestrator;
import com.wisread.service.KnowledgeRetrievalGrader;
import com.wisread.service.KnowledgeVectorIndexingService;
import com.wisread.service.QueryRewriteService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排器单测（spec 4.2.1 编排流程 + 4.2.2 deadline + 4.6 降级决策表 + §8.C/§8.D 清单）。
 * 所有降级路径收敛为 RetrievalOutcome，调用方仅按 candidates 是否为空走既有生成/拒答逻辑。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeRagOrchestratorTest {

    private static final float[] FIRST_EMBEDDING = {0.1f, 0.2f};
    private static final float[] RETRY_EMBEDDING = {0.4f, 0.4f};

    @Mock
    private KnowledgeVectorIndexingService knowledgeVectorIndexingService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private QueryRewriteService queryRewriteService;

    @Mock
    private KnowledgeRetrievalGrader grader;

    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;

    private SimpleMeterRegistry meterRegistry;

    // 知识库配置与线上默认对齐：topK=10、rerankK=3、阈值 0.65（阈值过滤 distance ≤ 0.35）
    private KnowledgeBase kb() {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(7L);
        knowledgeBase.setTopK(10);
        knowledgeBase.setSimilarityThreshold(0.65);
        knowledgeBase.setRerankEnabled(true);
        knowledgeBase.setRerankK(3);
        knowledgeBase.setAgentEnabled(true);
        return knowledgeBase;
    }

    // 构造检索服务原始命中（searchKnowledgeChunks 返回 KnowledgeChunkSearchResult，
    // 经管线 filterAndTruncate 转为 ChunkSearchResult——测试距离全部 ≤ 1-0.65，过阈值）
    private KnowledgeChunkSearchResult hit(long chunkId, double distance) {
        return new KnowledgeChunkSearchResult(chunkId, 100L, "test.pdf", "c" + chunkId, 1, 1, "页", distance);
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    private KnowledgeRagOrchestrator orchestrator(boolean globalEnabled, long budgetMs, int concurrency) {
        return new KnowledgeRagOrchestratorImpl(knowledgeVectorIndexingService, embeddingService,
                queryRewriteService, grader, knowledgeBaseRepository, meterRegistry,
                globalEnabled, budgetMs, concurrency, 10);
    }

    private void stubFirstSearch(List<KnowledgeChunkSearchResult> results) {
        lenient().when(knowledgeVectorIndexingService.searchKnowledgeChunks(eq(1L), eq(7L), any(float[].class), anyInt()))
                .thenReturn(results);
    }

    private void stubGrade(GradeResult result) {
        lenient().when(grader.grade(anyList(), any(KnowledgeBase.class), anyLong())).thenReturn(result);
        lenient().when(knowledgeBaseRepository.countIndexedChunksByKnowledgeBaseId(7L)).thenReturn(100L);
    }

    private void stubSuccessfulRetry() {
        lenient().when(queryRewriteService.rewriteForRetry(anyString(), eq(1L)))
                .thenReturn(RetryRewriteResult.rewritten("新检索词"));
        lenient().when(embeddingService.embed(anyList(), eq(1L))).thenReturn(List.of(RETRY_EMBEDDING));
        lenient().when(knowledgeVectorIndexingService.searchKnowledgeChunks(eq(1L), eq(7L), eq(RETRY_EMBEDDING), anyInt()))
                .thenReturn(List.of(hit(4, 0.08)));
    }

    @Test
    void agentDisabledPassthroughNoGrader() {
        // 全局总闸关闭：透传单轮结果，评估器与 count 查询均不执行（AC1/§8.D 零额外 SQL）
        stubFirstSearch(List.of(hit(1, 0.10), hit(2, 0.15)));

        RetrievalOutcome outcome = orchestrator(false, 8000, 8)
                .orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);

        assertThat(outcome.candidates()).hasSize(2);
        assertThat(outcome.rounds()).isEqualTo(1);
        assertThat(outcome.retryStatus()).isNull();
        verify(grader, never()).grade(anyList(), any(), anyLong());
        verify(knowledgeBaseRepository, never()).countIndexedChunksByKnowledgeBaseId(anyLong());
        verify(queryRewriteService, never()).rewriteForRetry(anyString(), anyLong());
    }

    @Test
    void kbSwitchOffPassthroughNoGrader() {
        // 库级开关关闭：同样透传（回滚主路径，即时生效）
        KnowledgeBase knowledgeBase = kb();
        knowledgeBase.setAgentEnabled(false);
        stubFirstSearch(List.of(hit(1, 0.10)));

        RetrievalOutcome outcome = orchestrator(true, 8000, 8)
                .orchestrate("问题", FIRST_EMBEDDING, knowledgeBase, 1L);

        assertThat(outcome.candidates()).hasSize(1);
        assertThat(outcome.rounds()).isEqualTo(1);
        verify(grader, never()).grade(anyList(), any(), anyLong());
        verify(knowledgeBaseRepository, never()).countIndexedChunksByKnowledgeBaseId(anyLong());
    }

    @Test
    void sufficientFirstRoundNoRetry() {
        // 首轮判定充分：仅 1 轮检索、1 次调用方已算好的向量复用（不重复 embed）、无补检（AC2/US2）
        stubFirstSearch(List.of(hit(1, 0.10), hit(2, 0.15), hit(3, 0.20)));
        stubGrade(new GradeResult(true, GradeResult.Reason.SUFFICIENT));

        RetrievalOutcome outcome = orchestrator(true, 8000, 8)
                .orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);

        assertThat(outcome.candidates()).hasSize(3);
        assertThat(outcome.rounds()).isEqualTo(1);
        assertThat(outcome.retryStatus()).isNull();
        verify(knowledgeVectorIndexingService, times(1))
                .searchKnowledgeChunks(anyLong(), anyLong(), any(float[].class), anyInt());
        verify(embeddingService, never()).embed(anyList(), anyLong());
    }

    @Test
    void smallCorpusSkipsRetry() {
        // SMALL_CORPUS：跳过补检（豁免 ≠ 充分），独立 reason 呈现（spec 4.2.4）
        stubFirstSearch(List.of(hit(1, 0.10)));
        stubGrade(new GradeResult(false, GradeResult.Reason.SMALL_CORPUS));

        RetrievalOutcome outcome = orchestrator(true, 8000, 8)
                .orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);

        assertThat(outcome.candidates()).hasSize(1);
        assertThat(outcome.retryStatus()).isEqualTo(RetrievalOutcome.RETRY_SKIPPED);
        assertThat(outcome.degradationReason()).isEqualTo("small_corpus");
        verify(queryRewriteService, never()).rewriteForRetry(anyString(), anyLong());
    }

    @Test
    void graderExceptionTreatedAsSufficient() {
        // 评估器自身异常：按充分处理让路给生成，记 grader_error（spec 4.2.4 / Task 3 裁定）
        stubFirstSearch(List.of(hit(1, 0.10), hit(2, 0.15), hit(3, 0.20)));
        when(grader.grade(anyList(), any(KnowledgeBase.class), anyLong()))
                .thenThrow(new RuntimeException("评估器异常"));
        when(knowledgeBaseRepository.countIndexedChunksByKnowledgeBaseId(7L)).thenReturn(100L);

        RetrievalOutcome outcome = orchestrator(true, 8000, 8)
                .orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);

        assertThat(outcome.candidates()).hasSize(3);
        assertThat(outcome.retryStatus()).isEqualTo(RetrievalOutcome.RETRY_SKIPPED);
        assertThat(outcome.degradationReason()).isEqualTo("grader_error");
    }

    @Test
    void insufficientTriggersRetryAndMerge() {
        // 首轮不充分 → 补检成功 → 合并去重 + 保底配额截断（AC3/AC8a/AC8b）
        stubFirstSearch(List.of(hit(1, 0.10), hit(2, 0.15), hit(3, 0.20)));
        stubGrade(new GradeResult(false, GradeResult.Reason.LOW_HITS));
        stubSuccessfulRetry();

        RetrievalOutcome outcome = orchestrator(true, 8000, 8)
                .orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);

        // K=3：G=2 首轮保底 1、2；补检新增 4(0.08) 取 1 席；3(0.20) 被挤出
        assertThat(outcome.candidates()).extracting(ChunkSearchResult::chunkId)
                .containsExactly(4L, 1L, 2L);
        assertThat(outcome.rounds()).isEqualTo(2);
        assertThat(outcome.retryStatus()).isEqualTo(RetrievalOutcome.RETRY_COMPLETED);
        assertThat(outcome.degradationReason()).isNull();
        assertThat(outcome.iterations()).hasSize(2);
        // 补检检索使用新向量；首轮复用调用方向量（embed 只调一次，即补检那次）
        verify(knowledgeVectorIndexingService).searchKnowledgeChunks(eq(1L), eq(7L), eq(RETRY_EMBEDDING), anyInt());
        verify(embeddingService, times(1)).embed(anyList(), anyLong());
    }

    @Test
    void firstRoundEmptyRetryPassthroughReturnsEmpty() {
        // 首轮为空且补检改写失败：候选为空 → 调用方走既有拒答（AC4/§8.C）
        stubFirstSearch(List.of());
        stubGrade(new GradeResult(false, GradeResult.Reason.NO_HITS));
        when(queryRewriteService.rewriteForRetry(anyString(), eq(1L)))
                .thenReturn(RetryRewriteResult.of(RetryRewriteResult.Type.PASSTHROUGH));

        RetrievalOutcome outcome = orchestrator(true, 8000, 8)
                .orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);

        assertThat(outcome.candidates()).isEmpty();
        assertThat(outcome.retryStatus()).isEqualTo(RetrievalOutcome.RETRY_SKIPPED);
        assertThat(outcome.degradationReason()).isEqualTo("rewrite_passthrough");
        verify(knowledgeVectorIndexingService, times(1))
                .searchKnowledgeChunks(anyLong(), anyLong(), any(float[].class), anyInt());
    }

    @Test
    void rewriteInvalidSkipsRetrySearch() {
        // 改写结果与输入相同（INVALID）：调用已计量但绝不重复执行相同查询（§8.C）
        stubFirstSearch(List.of(hit(1, 0.10)));
        stubGrade(new GradeResult(false, GradeResult.Reason.LOW_HITS));
        when(queryRewriteService.rewriteForRetry(anyString(), eq(1L)))
                .thenReturn(RetryRewriteResult.of(RetryRewriteResult.Type.INVALID));

        RetrievalOutcome outcome = orchestrator(true, 8000, 8)
                .orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);

        assertThat(outcome.candidates()).hasSize(1);
        assertThat(outcome.degradationReason()).isEqualTo("rewrite_invalid");
        verify(knowledgeVectorIndexingService, times(1))
                .searchKnowledgeChunks(anyLong(), anyLong(), any(float[].class), anyInt());
        verify(embeddingService, never()).embed(anyList(), anyLong());
    }

    @Test
    void embedFailureSkipsRetry() {
        // 补检 embed 失败：降级首轮候选生成（§8.C）
        stubFirstSearch(List.of(hit(1, 0.10)));
        stubGrade(new GradeResult(false, GradeResult.Reason.LOW_HITS));
        when(queryRewriteService.rewriteForRetry(anyString(), eq(1L)))
                .thenReturn(RetryRewriteResult.rewritten("新检索词"));
        when(embeddingService.embed(anyList(), eq(1L))).thenThrow(new RuntimeException("embedding 不可用"));

        RetrievalOutcome outcome = orchestrator(true, 8000, 8)
                .orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);

        assertThat(outcome.candidates()).hasSize(1);
        assertThat(outcome.candidates().get(0).chunkId()).isEqualTo(1L);
        assertThat(outcome.degradationReason()).isEqualTo("embedding_failed");
    }

    @Test
    void budgetTimeoutReturnsPromptlyWithoutRetrySearch() {
        // 改写阶段超时：请求线程按时返回，不触发后续 embed/检索（§8.C deadline 可取消）
        stubFirstSearch(List.of(hit(1, 0.10)));
        stubGrade(new GradeResult(false, GradeResult.Reason.LOW_HITS));
        when(queryRewriteService.rewriteForRetry(anyString(), eq(1L))).thenAnswer(invocation -> {
            Thread.sleep(2000);
            return RetryRewriteResult.rewritten("太晚了");
        });

        long start = System.currentTimeMillis();
        RetrievalOutcome outcome = orchestrator(true, 100, 8)
                .orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(1500);
        assertThat(outcome.candidates()).hasSize(1);
        assertThat(outcome.degradationReason()).isEqualTo("budget_exceeded");
        verify(embeddingService, never()).embed(anyList(), anyLong());
        verify(knowledgeVectorIndexingService, times(1))
                .searchKnowledgeChunks(anyLong(), anyLong(), any(float[].class), anyInt());
    }

    @Test
    void concurrencyLimitSkipsRetryImmediately() {
        // 并发护栏满（tryAcquire 失败）：立即降级首轮结果，不等待（AC9）
        stubFirstSearch(List.of(hit(1, 0.10)));
        stubGrade(new GradeResult(false, GradeResult.Reason.LOW_HITS));
        KnowledgeRagOrchestratorImpl impl = (KnowledgeRagOrchestratorImpl) orchestrator(true, 8000, 1);
        Semaphore permits = (Semaphore) ReflectionTestUtils.getField(impl, "retryPermits");
        permits.tryAcquire(); // 占满唯一许可，模拟补检高峰

        long start = System.currentTimeMillis();
        RetrievalOutcome outcome = impl.orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(500);
        assertThat(outcome.candidates()).hasSize(1);
        assertThat(outcome.degradationReason()).isEqualTo("concurrency_limit");
        verify(queryRewriteService, never()).rewriteForRetry(anyString(), anyLong());
    }

    @Test
    void insufficientFinalMetricWhenRetryDoesntHelp() {
        // 补检后最终候选仍不充分：agent_insufficient_final_total 计数（观测指标，spec 4.5）
        stubFirstSearch(List.of(hit(1, 0.10)));
        stubGrade(new GradeResult(false, GradeResult.Reason.LOW_HITS));
        stubSuccessfulRetry();
        // 最终合并结果仍被判不充分
        when(grader.grade(anyList(), any(KnowledgeBase.class), anyLong()))
                .thenReturn(new GradeResult(false, GradeResult.Reason.LOW_HITS))
                .thenReturn(new GradeResult(false, GradeResult.Reason.LOW_HITS));

        orchestrator(true, 8000, 8).orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);

        assertThat(meterRegistry.counter("agent_insufficient_final_total").count()).isEqualTo(1.0);
        // count 查询只发生一次（首轮评估与最终观测共用）
        verify(knowledgeBaseRepository, times(1)).countIndexedChunksByKnowledgeBaseId(7L);
    }

    @Test
    void retrySearchReusesCallerEmbeddingForFirstRound() {
        // 入口不变式：首轮检索必须使用调用方传入的向量（不重复 embed、不重复计量，AC7）
        ArgumentCaptor<float[]> embeddingCaptor = ArgumentCaptor.forClass(float[].class);
        stubFirstSearch(List.of(hit(1, 0.10)));
        stubGrade(new GradeResult(true, GradeResult.Reason.SUFFICIENT));

        orchestrator(true, 8000, 8).orchestrate("问题", FIRST_EMBEDDING, kb(), 1L);

        verify(knowledgeVectorIndexingService)
                .searchKnowledgeChunks(eq(1L), eq(7L), embeddingCaptor.capture(), anyInt());
        assertThat(embeddingCaptor.getValue()).isSameAs(FIRST_EMBEDDING);
    }
}
