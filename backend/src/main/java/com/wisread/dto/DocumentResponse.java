package com.wisread.dto;

import java.time.Instant;

/**
 * 文档响应 DTO（DocumentResponse）。
 * 用于返回知识库中文档的完整信息及处理状态。
 */
public record DocumentResponse(
        // 文档 ID
        Long id,
        // 文档所属的项目 ID
        Long projectId,
        // 文件名（含扩展名）
        String filename,
        // 文件大小（单位：字节）
        Long fileSize,
        // 文档页数（解析后统计）
        Integer pageCount,
        // 文档分块后的 token 总数（解析后统计）
        Integer tokenCount,
        // 文档处理状态（如 PENDING / PROCESSING / DONE / FAILED）
        String status,
        // 处理失败时的错误描述；成功时为 null
        String errorMessage,
        // 文档创建时间
        Instant createdAt,
        // 文档最近更新时间
        Instant updatedAt
) {
}
