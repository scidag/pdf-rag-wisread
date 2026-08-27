package com.wisread.dto;

import java.time.Instant;

/**
 * 项目响应 DTO（ProjectResponse）。
 * 用于返回知识库项目的详细信息，包含文档数、会话数等统计。
 */
public record ProjectResponse(
        // 项目 ID
        Long id,
        // 项目名称
        String name,
        // 项目描述
        String description,
        // 项目下文档数量
        long documentCount,
        // 项目下会话数量
        long conversationCount,
        // 项目创建时间
        Instant createdAt,
        // 项目最近更新时间
        Instant updatedAt
) {
}
