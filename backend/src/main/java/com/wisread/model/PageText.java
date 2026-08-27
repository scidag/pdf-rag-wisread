package com.wisread.model;

/**
 * 单页文本（内存数据模型，不持久化）。
 * 在解析 PDF 等文档时，抽取出的每一页文本封装为该记录，
 * 供后续分块（TextChunk）处理使用。
 */
public record PageText(
        int page, // 页码，从 1 开始
        String text // 该页提取出的纯文本内容
) {
}
