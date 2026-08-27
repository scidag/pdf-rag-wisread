package com.wisread.dto;

import java.time.Instant;

/**
 * 会话响应 DTO（ConversationResponse）。
 * 用于返回对话会话的概要信息。
 */
public record ConversationResponse(
        // 会话 ID
        Long id,
        // 会话所属的项目 ID
        Long projectId,
        // 会话标题
        String title,
        // 会话创建时间
        Instant createdAt,
        // 会话最近更新时间
        Instant updatedAt
) {
}
