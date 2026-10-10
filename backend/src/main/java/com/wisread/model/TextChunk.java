package com.wisread.model;

/**
 * 文本分块结果（内存数据模型，不持久化）。
 * 文档解析后，文本被切分为多个片段，每个片段即一个 TextChunk，
 * 包含片段内容、对应的定位区间、定位单位标签与 token 数，
 * 随后持久化为 DocumentChunk 并向量化。
 */
public record TextChunk(
        String content, // 分块的文本内容
        int pageStart, // 分块起始定位序号（含）
        int pageEnd, // 分块结束定位序号（含）
        String locationLabel, // 定位单位标签，例如“页”“段落”“行”“工作表 Sheet1”
        int tokenCount // 分块的 token 数量
) {
    /**
     * 兼容旧的“页码”构造方式，默认定位单位是“页”。
     */
    public TextChunk(String content, int pageStart, int pageEnd, int tokenCount) {
        this(content, pageStart, pageEnd, "页", tokenCount);
    }
}
