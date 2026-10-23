package com.wisread.dto;

import java.util.List;

/**
 * 知识库检索测试响应 DTO（KnowledgeRetrievalTestResponse）。
 * 一次 RAG 全链路测试的分阶段结果：改写、召回、重排、答案与引用、
 * 本次实际使用的检索配置快照与各阶段耗时（毫秒）。
 */
public record KnowledgeRetrievalTestResponse(
        // 改写阶段结果
        RewriteInfo rewrite,
        // 召回阶段命中列表（阈值过滤 + rerankK 截断后，按距离升序）
        List<RecallItem> recall,
        // 重排阶段信息（当前为占位实现）
        RerankInfo rerank,
        // 基于命中片段生成的答案；rejected=true 时为拒答文案
        String answer,
        // 是否拒答（命中为空或全部低于阈值）
        boolean rejected,
        // 答案中解析出的引用列表（rejected 时为空列表）
        List<SourceResponse> citations,
        // 本次实际使用的检索配置快照
        UsedConfig usedConfig,
        // 各阶段耗时（毫秒）
        Timings timings,
        // Agentic RAG 各轮次明细（agent 关闭时为单轮，历史响应无该字段）
        List<IterationInfo> iterations,
        // Agentic RAG 补检状态摘要（agent 关闭时两字段均为 null）
        AgentInfo agent
) {

    /**
     * 改写阶段结果。当前链路仅多轮对话有 LLM 改写（依赖历史消息），
     * 检索测试无历史时走 passthrough（原问题直接进入检索）。
     */
    public record RewriteInfo(
            // 原始问题
            String originalQuestion,
            // 改写后的问题（未改写时与 originalQuestion 相同）
            String rewrittenQuestion,
            // 是否发生了改写
            boolean rewritten,
            // 改写模式：passthrough（未改写直接透传）或 llm（调用 LLM 改写）
            String mode
    ) {
    }

    /**
     * 一条召回命中（截断后保留的片段）。
     */
    public record RecallItem(
            // 切片ID（knowledge_chunks.id）
            Long chunkId,
            // 所属知识库文档ID（knowledge_documents.id）
            Long documentId,
            // 文件名
            String filename,
            // 起始定位序号（含）
            int pageStart,
            // 结束定位序号（含）
            int pageEnd,
            // 定位单位标签
            String locationLabel,
            // 向量余弦距离（越小越相似）
            double distance,
            // 余弦相似度 = 1 - 距离
            double similarity,
            // 内容摘要（超长截断）
            String snippet
    ) {
    }

    /**
     * 重排阶段信息。当前 rerank 为占位实现：不接重排模型，仅按向量距离排序并按 rerankK 截断。
     */
    public record RerankInfo(
            // 本次测试时知识库的重排开关
            Boolean enabled,
            // 策略标识：placeholder（占位）
            String strategy,
            // 中文说明
            String note
    ) {
    }

    /**
     * 本次测试实际使用的检索配置快照（与召回行为严格一致）。
     */
    public record UsedConfig(
            // 实际使用的召回上限（知识库未配置时为回退默认值）
            Integer topK,
            // 相似度阈值（null 表示不过滤）
            Double similarityThreshold,
            // 重排开关
            Boolean rerankEnabled,
            // rerankK 截断数
            Integer rerankK
    ) {
    }

    /**
     * 各阶段耗时（毫秒）。未执行的阶段为 0（如拒答时未调用答案生成）。
     */
    public record Timings(
            // 改写阶段耗时
            long rewriteMs,
            // 召回阶段耗时（含 query embedding 与向量检索）
            long recallMs,
            // 重排阶段耗时
            long rerankMs,
            // 答案生成阶段耗时
            long answerMs
    ) {
    }

    /**
     * Agentic RAG 一轮检索的明细（与编排器 RetrievalIteration 同形映射）。
     */
    public record IterationInfo(
            // 轮次序号（从 1 开始）
            int round,
            // 本轮实际使用的检索查询
            String query,
            // 本轮命中条数
            int hits,
            // 本轮充分性评估结果
            boolean sufficient,
            // 评估原因（如 SUFFICIENT、LOW_HITS、budget_exceeded 等）
            String reason,
            // 本轮耗时（毫秒）
            long elapsedMs
    ) {
    }

    /**
     * Agentic RAG 补检状态摘要。agent 关闭时两字段均为 null（保持单轮行为）。
     */
    public record AgentInfo(
            // 补检状态：COMPLETED / SKIPPED，未触发补检时为 null
            String retryStatus,
            // 降级原因（如 concurrency_limit、budget_exceeded、grader_error），无降级时为 null
            String degradationReason
    ) {
    }
}
