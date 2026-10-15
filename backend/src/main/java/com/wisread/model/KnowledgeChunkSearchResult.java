package com.wisread.model;

/**
 * 知识库检索命中结果（内存数据模型，不持久化）。
 * 在知识库向量检索阶段，根据用户问题在指定知识库下匹配出若干文档分块，
 * 该记录封装一次命中返回的关键信息，供后续拼接上下文与展示引用使用。
 */
public record KnowledgeChunkSearchResult(
        Long chunkId, // 命中分块的ID（knowledge_chunks.id）
        Long documentId, // 命中分块所属知识库文档的ID（knowledge_documents.id）
        String filename, // 命中分块所属文档的文件名，便于前端展示来源
        String content, // 命中分块的原文内容，用于拼接大模型上下文
        int pageStart, // 命中分块起始定位序号（含）
        int pageEnd, // 命中分块结束定位序号（含）
        String locationLabel, // 定位单位标签，例如“页”“段落”“行”“工作表 Sheet1”
        double distance // 向量余弦距离（越小表示越相似），用于衡量相关度
) {
}
