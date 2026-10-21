package com.wisread.model;

import java.util.List;

/**
 * 编排器检索结果（Agentic RAG 阶段 1，spec 4.2.1 RetrievalOutcome）。
 * 所有路径（含降级）收敛为本对象；调用方仅按 candidates 是否为空走既有生成/拒答逻辑，
 * 不感知内部降级细节（降级细节经 retryStatus / degradationReason / iterations 供观测）。
 *
 * @param candidates        最终注入列表（"当前最优结果"：最后一个成功完成的检索轮次经
 *                          filterAndTruncate 过滤后的候选；两轮均成功时为合并池按保底配额截断后的最终列表）
 * @param rounds            实际执行的检索轮次（1 或 2）
 * @param retryStatus       补检状态：null=未尝试（开关关闭或首轮充分）/ COMPLETED / SKIPPED
 * @param degradationReason 降级原因（spec 4.6 表值：small_corpus / rewrite_passthrough /
 *                          rewrite_invalid / embedding_failed / concurrency_limit /
 *                          budget_exceeded / grader_error；未降级为 null）
 * @param iterations        每轮明细（轮次、命中数、判定、耗时）
 */
public record RetrievalOutcome(
        List<ChunkSearchResult> candidates,
        int rounds,
        String retryStatus,
        String degradationReason,
        List<RetrievalIteration> iterations
) {

    /** 补检已实际执行 */
    public static final String RETRY_COMPLETED = "COMPLETED";

    /** 补检被跳过（降级原因见 degradationReason） */
    public static final String RETRY_SKIPPED = "SKIPPED";
}
