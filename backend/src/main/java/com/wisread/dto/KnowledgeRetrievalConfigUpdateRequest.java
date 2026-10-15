package com.wisread.dto;

/**
 * 更新知识库检索配置请求 DTO（KnowledgeRetrievalConfigUpdateRequest）。
 * 用于部分更新知识库的检索配置；仅覆盖请求中提供的字段（null 表示不修改）。
 * 取值校验在服务层完成（中文错误消息 + 跨字段 rerank_k ≤ top_k 校验）。
 */
public record KnowledgeRetrievalConfigUpdateRequest(
        // 检索召回片段数上限，取值 1~50
        Integer topK,
        // 检索相似度阈值，取值 0~1
        Double similarityThreshold,
        // 是否启用重排（rerank）
        Boolean rerankEnabled,
        // 重排后保留的片段数，≥ 1 且 ≤ topK
        Integer rerankK
) {
}
