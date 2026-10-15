package com.wisread.model;

/**
 * 知识库文档切片分页视图（内存数据模型，不持久化）。
 * 用于分页列出某知识库文档的切片明细，不含 embedding 向量（向量体积大且前端无需展示）。
 */
public record KnowledgeChunkView(
        int chunkIndex, // 全局分块序号，从 0 开始，跨页连续
        String content, // 切片文本内容
        int pageStart, // 切片起始定位序号（含）
        int pageEnd, // 切片结束定位序号（含）
        String locationLabel, // 定位单位标签，例如“页”“段落”“行”“工作表 Sheet1”
        int tokenCount // 切片的 token 数量
) {
}
