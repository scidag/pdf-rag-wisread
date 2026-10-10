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
        String locationLabel, // 定位单位标签，例如“页”“段落”“行”“工作表 Sheet1”
        double distance // 向量距离（越小表示越相似），用于衡量相关度
) {
    /**
     * 兼容旧的“页码”构造方式，默认定位单位是“页”。
     */
    public ChunkSearchResult(
            Long chunkId,
            Long documentId,
            String filename,
            String content,
            int pageStart,
            int pageEnd,
            double distance
    ) {
        this(chunkId, documentId, filename, content, pageStart, pageEnd, "页", distance);
    }
}
