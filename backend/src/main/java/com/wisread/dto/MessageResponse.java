package com.wisread.dto;

import java.time.Instant;
import java.util.List;

/**
 * 消息响应 DTO（MessageResponse）。
 * 用于返回会话中的一条消息（用户提问或助手回答），含来源引用。
 */
public record MessageResponse(
        // 消息 ID
        Long id,
        // 消息角色（如 "user" 表示用户提问，"assistant" 表示助手回答）
        String role,
        // 消息文本内容
        String content,
        // 该回答引用的知识来源列表（RAG 检索到的片段）
        List<SourceResponse> sources,
        // 消息创建时间
        Instant createdAt
) {
}
