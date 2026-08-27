package com.wisread.dto;

/**
 * 知识来源响应 DTO（SourceResponse）。
 * 用于表示一次问答中，助手回答所引用的知识库片段来源（RAG 检索结果）。
 */
public record SourceResponse(
        // 来源序号（在多个来源中的顺序下标）
        int index,
        // 来源对应的文档分块（chunk）ID
        Long chunkId,
        // 来源所属文档 ID
        Long documentId,
        // 来源文件名
        String filename,
        // 来源起始页码
        int pageStart,
        // 来源结束页码
        int pageEnd,
        // 来源文本片段（摘录内容）
        String snippet
) {
}
