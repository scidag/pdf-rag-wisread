package com.wisread.service;

import com.wisread.model.ChunkSearchResult;

import java.util.List;

/**
 * 重排（Rerank）服务接口。
 *
 * <p>在向量召回（粗排）之后，对候选文本块做更精细的相关性重排序，使与问题最相关的块排在前面，
 * 从而被 Prompt 优先引用、被距离阈值更可靠地筛选。当前 RAG 流程中 rerank 在
 * 向量检索 Top10 之后、距离阈值拒答之前被调用。
 */
public interface RerankService {

    /**
     * 对候选块列表做重排序。
     *
     * @param query      改写后的查询问题
     * @param candidates 向量召回的候选块（含与查询的距离）
     * @return 重排后的候选块（顺序更相关优先，通常数量被截断）
     */
    List<ChunkSearchResult> rerank(String query, List<ChunkSearchResult> candidates);
}
