package com.wisread.service;

import com.wisread.entity.KnowledgeBase;
import com.wisread.model.RetrievalOutcome;

/**
 * 知识库 Agentic RAG 编排器接口（阶段 1，spec 4.2.1）：Chat 绑库问答与检索测试端点共用的唯一检索入口。
 *
 * <p>做什么：编排"检索 → 评估 →（改写 → 重新 embed → 补检）→ 合并"的有界流程——
 * 首轮经 {@link KnowledgeRetrievalPipeline} 统一口径检索后由评估器判定充分性，
 * 不充分时在统一 deadline 预算内自动改写并补检索至多 1 次，合并去重后返回最终候选。
 *
 * <p>输入不变式（调用方保证）：query 与 queryEmbedding 必须是同一文本的产物
 * （首轮复用调用方已算好的向量，不重复 embed、不重复计量）。
 *
 * <p>输出契约：所有路径（含降级）收敛为 {@link RetrievalOutcome}，
 * 调用方仅按 candidates 是否为空走既有生成/拒答逻辑，不感知内部降级细节。
 */
public interface KnowledgeRagOrchestrator {

    /**
     * 编排一次绑库检索（含按需补检）。
     *
     * @param query          首轮改写后的独立问题（已含指代消解）
     * @param queryEmbedding 该问题的向量（调用方已算好）
     * @param knowledgeBase  知识库配置（含 agent_enabled 库级开关）
     * @param userId         当前用户 ID
     * @return 检索结果与轮次明细
     */
    RetrievalOutcome orchestrate(String query, float[] queryEmbedding, KnowledgeBase knowledgeBase, Long userId);
}
