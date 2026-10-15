package com.wisread.dto;

/**
 * 知识库检索配置响应 DTO（KnowledgeRetrievalConfigResponse）。
 * 返回指定知识库当前生效的检索配置，供前端展示与编辑回填。
 */
public record KnowledgeRetrievalConfigResponse(
        // 检索召回片段数上限（1~50）
        Integer topK,
        // 检索相似度阈值（0~1），低于阈值的片段不参与召回
        Double similarityThreshold,
        // 是否启用重排（rerank）
        Boolean rerankEnabled,
        // 重排后保留的片段数（≤ topK）
        Integer rerankK
) {
}
