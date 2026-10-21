package com.wisread.service;

import com.wisread.entity.KnowledgeBase;
import com.wisread.model.ChunkSearchResult;
import com.wisread.model.GradeResult;
import com.wisread.service.impl.KnowledgeRetrievalPipeline;

import java.util.List;

/**
 * 知识库检索充分性评估器接口（Agentic RAG 阶段 1）。
 *
 * <p>做什么：对过滤截断后的候选片段判定"检索结果是否充分"，不充分时由编排器触发至多一次补检索。
 * 为什么：单轮 RAG 一次检索定成败，命中偏少或相似度偏低时需要自动换检索角度重试一次。
 *
 * <p>输入契约：candidates 必须是 {@link KnowledgeRetrievalPipeline#filterAndTruncate} 的输出
 * （已经过阈值过滤与 rerankK 截断）；indexedChunkCount 为该库已索引（INDEXED）chunk 总数。
 * 相似度口径唯一定义：余弦距离 = 1 - 余弦相似度，实现内部 similarity = 1 - distance。
 *
 * <p>预留：LLM 评估器实现位（同接口），本期不实现。
 */
public interface KnowledgeRetrievalGrader {

    /**
     * 评估检索结果充分性。
     *
     * @param candidates        过滤截断后的候选片段列表（管线输出）
     * @param knowledgeBase     知识库配置（topK/rerank/阈值）
     * @param indexedChunkCount 该库已索引 chunk 总数（仅评估器实际运行时由调用方查询）
     * @return 判定结果（sufficient + reason）
     */
    GradeResult grade(List<ChunkSearchResult> candidates, KnowledgeBase knowledgeBase, long indexedChunkCount);

    /**
     * 单轮口径上限 K 的唯一定义点（spec 4.2.3）：
     * rerank 启用且 rerankK 已配置时 K = rerankK（每轮已截断到 K）；否则 K = topK（召回上限）。
     * 即"最终 Prompt 片段数上限 = 单轮可能的最大值"，Prompt 长度相对现状不劣化。
     *
     * @param knowledgeBase 知识库配置
     * @param fallbackTopK  知识库未配置 topK 时的回退默认值
     * @return 单轮口径上限 K
     */
    static int resolveSingleRoundCap(KnowledgeBase knowledgeBase, int fallbackTopK) {
        return Boolean.TRUE.equals(knowledgeBase.getRerankEnabled()) && knowledgeBase.getRerankK() != null
                ? knowledgeBase.getRerankK()
                : KnowledgeRetrievalPipeline.resolveTopK(knowledgeBase, fallbackTopK);
    }
}
