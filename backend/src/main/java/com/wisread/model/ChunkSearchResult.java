package com.wisread.model;

/**
 * 检索命中结果（内存数据模型，不持久化）。
 * 在向量检索阶段，系统根据用户问题匹配出若干文档分块，
 * 该类封装一次命中返回的关键信息，供后续拼接上下文与展示引用使用。
 */
public record ChunkSearchResult(
        Long chunkId, // 命中分块的ID（document_chunks.id）
        Long documentId, // 命中分块所属文档的ID
        String filename, // 命中分块所属文档的文件名，便于前端展示来源
        String content, // 命中分块的原文内容，用于拼接大模型上下文
        int pageStart, // 命中分块起始页码（含）
        int pageEnd, // 命中分块结束页码（含）
        double distance // 向量距离（越小表示越相似），用于衡量相关度
) {
}
