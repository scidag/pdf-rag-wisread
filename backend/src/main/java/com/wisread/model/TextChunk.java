package com.wisread.model;

/**
 * 文本分块结果（内存数据模型，不持久化）。
 * 文档解析后，文本被切分为多个片段，每个片段即一个 TextChunk，
 * 包含片段内容、对应的页码区间与 token 数，随后持久化为 DocumentChunk 并向量化。
 */
public record TextChunk(
        String content, // 分块的文本内容
        int pageStart, // 分块起始页码（含）
        int pageEnd, // 分块结束页码（含）
        int tokenCount // 分块的 token 数量
) {
}
