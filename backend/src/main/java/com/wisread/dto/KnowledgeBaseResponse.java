package com.wisread.dto;

import java.time.Instant;

/**
 * 知识库响应 DTO（KnowledgeBaseResponse）。
 * 用于返回知识库的详细信息，包含状态、检索配置与文档数、分块数统计。
 */
public record KnowledgeBaseResponse(
        // 知识库 ID
        Long id,
        // 知识库拥有者 ID
        Long userId,
        // 知识库名称
        String name,
        // 知识库描述
        String description,
        // 状态机：DRAFT(草稿)、PROCESSING(处理中)、ACTIVE(可用)、ARCHIVED(归档)
        String status,
        // 检索召回片段数上限
        Integer topK,
        // 检索相似度阈值
        Double similarityThreshold,
        // 是否启用重排（rerank）
        Boolean rerankEnabled,
        // 重排后保留的片段数
        Integer rerankK,
        // 知识库下文档数量
        long documentCount,
        // 知识库下分块（chunk）数量
        long chunkCount,
        // 知识库创建时间
        Instant createdAt,
        // 知识库最近更新时间
        Instant updatedAt
) {
}
