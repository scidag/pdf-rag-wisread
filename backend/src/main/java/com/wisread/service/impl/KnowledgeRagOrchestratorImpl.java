package com.wisread.service.impl;

import com.wisread.entity.KnowledgeBase;
import com.wisread.model.ChunkSearchResult;
import com.wisread.model.GradeResult;
import com.wisread.model.KnowledgeChunkSearchResult;
import com.wisread.model.RetrievalIteration;
import com.wisread.model.RetrievalOutcome;
import com.wisread.model.RetryRewriteResult;
import com.wisread.repository.KnowledgeBaseRepository;
import com.wisread.service.EmbeddingService;
import com.wisread.service.KnowledgeRagOrchestrator;
import com.wisread.service.KnowledgeRetrievalGrader;
import com.wisread.service.KnowledgeVectorIndexingService;
import com.wisread.service.QueryRewriteService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 知识库 Agentic RAG 编排器（阶段 1，spec 4.2.1）：编排"检索 → 评估 →（改写 → 重新 embed → 补检）→ 合并"
 * 的有界流程，Chat 绑库问答与检索测试端点共用此唯一入口，防止口径分叉。
 *
 * <p>关键不变式：
 * <ul>
 *   <li>所有轮次召回均经 {@link KnowledgeRetrievalPipeline#filterAndTruncate}，编排器不复制第二套过滤/截断逻辑；</li>
 *   <li>首轮复用调用方传入的向量（不重复 embed、不重复计量），补检 embed 计入用量（由 EmbeddingService 内部计量）；</li>
 *   <li>补检发生在生成信号量之前，负载由独立并发护栏（{@link #retryPermits}）约束；</li>
 *   <li>补检统一 deadline（spec 4.2.2）：单调时钟，各阶段共享剩余预算，每阶段执行前检查、
 *       执行中可取消（future.get(remaining) + cancel(true)），超时后禁止进入后续阶段；</li>
 *   <li>合并池包含首轮全部片段（AC8a），最终注入首轮保底 ceil(K/2) 席（AC8b）；</li>
 *   <li>所有路径收敛为 {@link RetrievalOutcome}（spec 4.6 降级决策表）。</li>
 * </ul>
 */
@Service
public class KnowledgeRagOrchestratorImpl implements KnowledgeRagOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRagOrchestratorImpl.class);

    /** 检索轮次上限（首轮 + 1 次补检），本期常量（spec 4.2.1） */
    private static final int MAX_RETRIEVAL_ROUNDS = 2;

    private final KnowledgeVectorIndexingService knowledgeVectorIndexingService;
    private final EmbeddingService embeddingService;
    private final QueryRewriteService queryRewriteService;
    private final KnowledgeRetrievalGrader grader;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final MeterRegistry meterRegistry;
    /** 全局总闸（启动期配置，发版级保险丝） */
    private final boolean agentEnabled;
    /** 补检统一预算（毫秒），覆盖改写 + embed + 检索环节 */
    private final long retryBudgetMs;
    private final int fallbackTopK;
    /** 补检独立并发护栏（非阻塞 tryAcquire，超限立即降级） */
    private final Semaphore retryPermits;
    /** 补检专用线程池（尺寸 = 并发护栏许可数，与护栏配合保证无排队等待） */
    private final ExecutorService retryExecutor;

    public KnowledgeRagOrchestratorImpl(
            KnowledgeVectorIndexingService knowledgeVectorIndexingService,
            EmbeddingService embeddingService,
            QueryRewriteService queryRewriteService,
            KnowledgeRetrievalGrader grader,
            KnowledgeBaseRepository knowledgeBaseRepository,
            MeterRegistry meterRegistry,
            @Value("${wisread.knowledge.agent.enabled:true}") boolean agentEnabled,
            @Value("${wisread.knowledge.agent.retry-budget-ms:8000}") long retryBudgetMs,
            @Value("${wisread.knowledge.agent.retry-concurrency:8}") int retryConcurrency,
            @Value("${wisread.knowledge.agent.fallback-top-k:10}") int fallbackTopK
    ) {
        this.knowledgeVectorIndexingService = knowledgeVectorIndexingService;
        this.embeddingService = embeddingService;
        this.queryRewriteService = queryRewriteService;
        this.grader = grader;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.meterRegistry = meterRegistry;
        this.agentEnabled = agentEnabled;
        this.retryBudgetMs = retryBudgetMs;
        this.fallbackTopK = fallbackTopK;
        this.retryPermits = new Semaphore(retryConcurrency);
        this.retryExecutor = Executors.newFixedThreadPool(retryConcurrency, runnable -> {
            Thread thread = new Thread(runnable, "kb-agent-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void shutdown() {
        retryExecutor.shutdownNow();
    }

    @Override
    public RetrievalOutcome orchestrate(String query, float[] queryEmbedding, KnowledgeBase knowledgeBase, Long userId) {
        int topK = KnowledgeRetrievalPipeline.resolveTopK(knowledgeBase, fallbackTopK);
        int capK = KnowledgeRetrievalGrader.resolveSingleRoundCap(knowledgeBase, fallbackTopK);
        // 首轮检索：复用调用方已算好的向量（不重复 embed、不重复计量）
        long firstRoundStart = System.nanoTime();
        List<ChunkSearchResult> firstRound = searchRound(userId, knowledgeBase, queryEmbedding, topK);
        long firstRoundElapsedMs = (System.nanoTime() - firstRoundStart) / 1_000_000;
        List<RetrievalIteration> iterations = new ArrayList<>();

        // 开关检查（全局总闸 && 库级开关）：任一关闭透传单轮结果，评估器与 count 查询均不执行（AC1）
        boolean agentOn = agentEnabled && Boolean.TRUE.equals(knowledgeBase.getAgentEnabled());
        if (!agentOn) {
            iterations.add(new RetrievalIteration(1, query, firstRound.size(), false, null, firstRoundElapsedMs));
            return new RetrievalOutcome(firstRound, 1, null, null, iterations);
        }

        // 评估（chunk count 仅在评估器实际运行时执行；评估器异常按充分处理，记 grader_error）
        long indexedChunkCount;
        GradeResult grade;
        try {
            indexedChunkCount = knowledgeBaseRepository.countIndexedChunksByKnowledgeBaseId(knowledgeBase.getId());
            grade = grader.grade(firstRound, knowledgeBase, indexedChunkCount);
        } catch (Exception e) {
            log.warn("检索充分性评估异常，按充分处理 knowledgeBaseId={}", knowledgeBase.getId(), e);
            iterations.add(new RetrievalIteration(1, query, firstRound.size(), true, "grader_error", firstRoundElapsedMs));
            incrementSkipped("grader_error");
            return new RetrievalOutcome(firstRound, 1, RetrievalOutcome.RETRY_SKIPPED, "grader_error", iterations);
        }

        if (grade.sufficient()) {
            log.info("Agent 检索第 1 轮充分 knowledgeBaseId={} hits={} reason={} elapsedMs={}",
                    knowledgeBase.getId(), firstRound.size(), grade.reason(), firstRoundElapsedMs);
            iterations.add(new RetrievalIteration(1, query, firstRound.size(), true, grade.reason().name(), firstRoundElapsedMs));
            return new RetrievalOutcome(firstRound, 1, null, null, iterations);
        }
        log.info("Agent 检索第 1 轮不充分 knowledgeBaseId={} hits={} reason={} elapsedMs={}",
                knowledgeBase.getId(), firstRound.size(), grade.reason(), firstRoundElapsedMs);
        iterations.add(new RetrievalIteration(1, query, firstRound.size(), false, grade.reason().name(), firstRoundElapsedMs));

        // 小库豁免：库规模足够小，补检预期无新增收益，跳过补检（豁免 ≠ 充分）
        if (grade.reason() == GradeResult.Reason.SMALL_CORPUS) {
            incrementSkipped("small_corpus");
            return new RetrievalOutcome(firstRound, 1, RetrievalOutcome.RETRY_SKIPPED, "small_corpus", iterations);
        }

        // 补检并发护栏：非阻塞 tryAcquire，超限立即降级不等待（AC9）
        if (!retryPermits.tryAcquire()) {
            log.info("补检并发护栏已满，降级首轮结果 knowledgeBaseId={}", knowledgeBase.getId());
            incrementSkipped("concurrency_limit");
            return new RetrievalOutcome(firstRound, 1, RetrievalOutcome.RETRY_SKIPPED, "concurrency_limit", iterations);
        }

        // 统一 deadline：单调时钟，各阶段共享剩余预算（spec 4.2.2）
        meterRegistry.counter("agent_retry_total").increment();
        Timer.Sample timerSample = Timer.start(meterRegistry);
        long deadlineNanos = System.nanoTime() + retryBudgetMs * 1_000_000;
        try {
            // 阶段 1：改写（rewriteForRetry 内部已计量；PASSTHROUGH/INVALID 直接结束补检）
            RetryRewriteResult rewrite;
            try {
                rewrite = executeStage(() -> queryRewriteService.rewriteForRetry(query, userId), deadlineNanos);
            } catch (BudgetExceededException e) {
                return budgetExceeded(firstRound, iterations, knowledgeBase);
            } catch (Exception e) {
                log.warn("补检改写异常，跳过补检 knowledgeBaseId={}", knowledgeBase.getId(), e);
                return skipped(firstRound, iterations, "rewrite_passthrough", knowledgeBase);
            }
            if (rewrite.type() == RetryRewriteResult.Type.PASSTHROUGH) {
                return skipped(firstRound, iterations, "rewrite_passthrough", knowledgeBase);
            }
            if (rewrite.type() == RetryRewriteResult.Type.INVALID) {
                // 调用已计量，但绝不重复执行相同查询
                return skipped(firstRound, iterations, "rewrite_invalid", knowledgeBase);
            }

            // 阶段 2：对改写后的检索词二次 embed（计入用量，EmbeddingService 内部计量）
            float[] retryEmbedding;
            try {
                retryEmbedding = executeStage(
                        () -> embeddingService.embed(List.of(rewrite.text()), userId).get(0), deadlineNanos);
            } catch (BudgetExceededException e) {
                return budgetExceeded(firstRound, iterations, knowledgeBase);
            } catch (Exception e) {
                log.error("补检 embedding 失败，降级首轮结果 knowledgeBaseId={}", knowledgeBase.getId(), e);
                return skipped(firstRound, iterations, "embedding_failed", knowledgeBase);
            }

            // 阶段 3：统一管线补检（独立过 filterAndTruncate，无旁路）
            List<ChunkSearchResult> retryRound;
            try {
                retryRound = executeStage(
                        () -> searchRound(userId, knowledgeBase, retryEmbedding, topK), deadlineNanos);
            } catch (BudgetExceededException e) {
                return budgetExceeded(firstRound, iterations, knowledgeBase);
            } catch (Exception e) {
                log.error("补检检索失败，降级首轮结果 knowledgeBaseId={}", knowledgeBase.getId(), e);
                return skipped(firstRound, iterations, "retry_search_failed", knowledgeBase);
            }
            long retryElapsedMs = (System.nanoTime() - (deadlineNanos - retryBudgetMs * 1_000_000)) / 1_000_000;
            log.info("Agent 检索第 2 轮（补检）完成 knowledgeBaseId={} hits={} elapsedMs={}",
                    knowledgeBase.getId(), retryRound.size(), retryElapsedMs);
            iterations.add(new RetrievalIteration(2, rewrite.text(), retryRound.size(), false, null, retryElapsedMs));

            // 合并去重 + 保底配额截断（AC8a / AC8b）
            List<ChunkSearchResult> merged = mergeWithFirstRoundQuota(firstRound, retryRound, capK);

            // 观测指标：补检后最终候选仍被判定不充分的次数（启发式观测值，非失败回答数）
            try {
                if (!grader.grade(merged, knowledgeBase, indexedChunkCount).sufficient()) {
                    meterRegistry.counter("agent_insufficient_final_total").increment();
                }
            } catch (Exception e) {
                log.debug("补检后最终充分性观测异常（不影响结果）knowledgeBaseId={}", knowledgeBase.getId());
            }

            return new RetrievalOutcome(merged, MAX_RETRIEVAL_ROUNDS, RetrievalOutcome.RETRY_COMPLETED, null, iterations);
        } finally {
            timerSample.stop(Timer.builder("agent_retry_duration").register(meterRegistry));
            retryPermits.release();
        }
    }

    /**
     * 执行补检单阶段：执行前检查剩余预算，不足即抛 {@link BudgetExceededException}（不启动该阶段）；
     * 执行中以 future.get(remaining) 等待，超时取消底层任务（释放请求线程与底层调用）。
     */
    private <T> T executeStage(Callable<T> stage, long deadlineNanos) throws Exception {
        long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000;
        if (remainingMs <= 0) {
            throw new BudgetExceededException();
        }
        Future<T> future = retryExecutor.submit(stage);
        try {
            return future.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时：中断底层调用，请求线程按时返回（客户端/SQL 自身超时为真实兜底）
            future.cancel(true);
            throw new BudgetExceededException();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception exception ? exception : e;
        }
    }

    private RetrievalOutcome budgetExceeded(List<ChunkSearchResult> firstRound,
                                            List<RetrievalIteration> iterations, KnowledgeBase knowledgeBase) {
        log.warn("补检预算耗尽，降级首轮结果 knowledgeBaseId={} budgetMs={}", knowledgeBase.getId(), retryBudgetMs);
        return skipped(firstRound, iterations, "budget_exceeded", knowledgeBase);
    }

    private RetrievalOutcome skipped(List<ChunkSearchResult> firstRound, List<RetrievalIteration> iterations,
                                     String reason, KnowledgeBase knowledgeBase) {
        incrementSkipped(reason);
        return new RetrievalOutcome(firstRound, 1, RetrievalOutcome.RETRY_SKIPPED, reason, iterations);
    }

    private void incrementSkipped(String reason) {
        meterRegistry.counter("agent_retry_skipped_total", "reason", reason).increment();
    }

    /**
     * 单轮检索：统一管线召回 + 过滤截断（唯一口径，无旁路）。
     */
    private List<ChunkSearchResult> searchRound(Long userId, KnowledgeBase knowledgeBase, float[] embedding, int topK) {
        List<KnowledgeChunkSearchResult> rawResults =
                knowledgeVectorIndexingService.searchKnowledgeChunks(userId, knowledgeBase.getId(), embedding, topK);
        return KnowledgeRetrievalPipeline.filterAndTruncate(rawResults, knowledgeBase);
    }

    /** 预算耗尽内部信号 */
    private static final class BudgetExceededException extends Exception {
    }

    /* ==================== 合并纯函数（spec 4.2.3，CandidateMergeTest 覆盖） ==================== */

    /**
     * 合并去重（AC8a）：以首轮列表为基底构成合并池——补检条目按 chunkId 查重，
     * 已存在时保留距离更小者（距离相等保留首轮实例），不存在则加入池中。
     */
    static List<ChunkSearchResult> dedup(List<ChunkSearchResult> firstRound, List<ChunkSearchResult> retryRound) {
        List<ChunkSearchResult> pool = new ArrayList<>(firstRound);
        for (ChunkSearchResult retryItem : retryRound) {
            int existingIndex = indexOfChunkId(pool, retryItem.chunkId());
            if (existingIndex < 0) {
                pool.add(retryItem);
            } else if (retryItem.distance() < pool.get(existingIndex).distance()) {
                pool.set(existingIndex, retryItem);
            }
        }
        return pool;
    }

    /**
     * 首轮保底配额合并（AC8b）：G = min(首轮片段数, ceil(capK/2)) 席保底给首轮；
     * 补检新增片段（chunkId 不在首轮列表中）按（距离, chunkId）升序取满剩余席位；
     * 最终按（距离, chunkId）升序输出（确定性排序，不依赖 Map 遍历顺序）。
     */
    static List<ChunkSearchResult> mergeWithFirstRoundQuota(
            List<ChunkSearchResult> firstRound, List<ChunkSearchResult> retryRound, int capK) {
        Set<Long> firstRoundChunkIds = new HashSet<>();
        for (ChunkSearchResult item : firstRound) {
            firstRoundChunkIds.add(item.chunkId());
        }
        List<ChunkSearchResult> newFromRetry = retryRound.stream()
                .filter(item -> !firstRoundChunkIds.contains(item.chunkId()))
                .sorted(DISTANCE_THEN_CHUNK_ID)
                .toList();
        int guaranteedSeats = Math.min(firstRound.size(), (capK + 1) / 2);
        List<ChunkSearchResult> result = new ArrayList<>(firstRound.subList(0, guaranteedSeats));
        int retrySeats = Math.min(capK - guaranteedSeats, newFromRetry.size());
        result.addAll(newFromRetry.subList(0, retrySeats));
        result.sort(DISTANCE_THEN_CHUNK_ID);
        return result;
    }

    private static final Comparator<ChunkSearchResult> DISTANCE_THEN_CHUNK_ID =
            Comparator.comparingDouble(ChunkSearchResult::distance)
                    .thenComparing(ChunkSearchResult::chunkId);

    private static int indexOfChunkId(List<ChunkSearchResult> pool, Long chunkId) {
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).chunkId().equals(chunkId)) {
                return i;
            }
        }
        return -1;
    }
}
