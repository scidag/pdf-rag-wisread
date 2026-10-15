package com.wisread.dto;

import java.util.List;

/**
 * 知识库文档切片分页响应 DTO（KnowledgeChunkPageResponse）。
 * 返回当前页切片明细与切片总数，供前端分页控件使用。
 */
public record KnowledgeChunkPageResponse(
        // 当前页切片列表
        List<KnowledgeChunkResponse> items,
        // 切片总数
        long total,
        // 当前页码（从 1 开始）
        int page,
        // 每页条数
        int size
) {
}
