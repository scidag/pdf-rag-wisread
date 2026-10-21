package com.wisread.model;

/**
 * 一轮检索的轮次明细（Agentic RAG 可观测，spec 4.5 / US3）。
 * 每轮检索（含首轮与补检轮）完成后记录，用于检索测试响应 iterations 字段与日志。
 *
 * @param round       轮次序号（1 = 首轮，2 = 补检轮）
 * @param query       该轮实际使用的检索问题（首轮为改写后问题，补检轮为再改写检索词）
 * @param hits        该轮过滤截断后的命中数
 * @param sufficient  该轮评估判定是否充分
 * @param reason      判定原因（GradeResult.Reason 名称；评估未运行时为 null）
 * @param elapsedMs   该轮耗时（毫秒）
 */
public record RetrievalIteration(
        int round,
        String query,
        int hits,
        boolean sufficient,
        String reason,
        long elapsedMs
) {
}
