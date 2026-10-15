package com.wisread.dto;

import java.time.Instant;

/**
 * 知识库文档响应 DTO（KnowledgeDocumentResponse）。
 * 用于返回知识库文档的详细信息，重点关注 status 字段供前端轮询处理进度。
 */
public record KnowledgeDocumentResponse(
        // 文档 ID
        Long id,
        // 所属知识库 ID
        Long knowledgeBaseId,
        // 原始文件名
        String filename,
        // 文档类型：pdf/docx/xlsx/txt/md
        String fileType,
        // 文件大小（字节）
        Long fileSize,
        // 定位单位总数（PDF 为页数等），解析后填充
        Integer pageCount,
        // 文档 token 总数，解析后填充
        Integer tokenCount,
        // 处理状态机：UPLOADED(已上传)/PROCESSING(处理中)/INDEXED(已索引)/FAILED(失败)
        String status,
        // 处理失败时的错误信息，成功时为 null
        String errorMessage,
        // 生成向量时使用的嵌入模型版本
        String embeddingModelVersion,
        // 创建时间
        Instant createdAt,
        // 最近更新时间
        Instant updatedAt
) {
}
