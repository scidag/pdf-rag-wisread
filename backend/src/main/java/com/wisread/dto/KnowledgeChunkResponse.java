package com.wisread.dto;

/**
 * 知识库文档切片响应 DTO（KnowledgeChunkResponse）。
 * 用于分页展示某文档的切片明细，不含 embedding 向量。
 */
public record KnowledgeChunkResponse(
        // 全局分块序号，从 0 开始，跨页连续
        int chunkIndex,
        // 切片文本内容
        String content,
        // 切片起始定位序号（含）
        int pageStart,
        // 切片结束定位序号（含）
        int pageEnd,
        // 定位单位标签，例如“页”“段落”“行”“工作表 Sheet1”
        String locationLabel,
        // 切片的 token 数量
        int tokenCount
) {
}
