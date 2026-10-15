package com.wisread.dto;

/**
 * 批量上传知识库文档的逐文件结果 DTO（KnowledgeDocumentBatchItemResponse）。
 * 批量上传时单文件失败不影响其他文件，每个文件对应一条结果：
 * success 为 true 时 document 有值；为 false 时 errorMessage 携带中文失败原因。
 */
public record KnowledgeDocumentBatchItemResponse(
        // 本次上传的文件名（文件为空时可能为 null）
        String filename,
        // 该文件是否上传成功
        boolean success,
        // 上传成功时的文档信息（失败时为 null）
        KnowledgeDocumentResponse document,
        // 上传失败时的中文失败原因（成功时为 null）
        String errorMessage
) {
}
