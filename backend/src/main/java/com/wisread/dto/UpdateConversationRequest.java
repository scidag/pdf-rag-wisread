package com.wisread.dto;

/**
 * 更新会话请求 DTO（UpdateConversationRequest）。
 * 用于 PATCH 会话（当前仅支持切换绑定的独立知识库）：
 * knowledgeBaseId 非空表示绑定该知识库；传 null 表示解绑，问答恢复项目检索链路。
 */
public record UpdateConversationRequest(
        // 目标知识库 ID；null 表示解绑
        Long knowledgeBaseId
) {
}
