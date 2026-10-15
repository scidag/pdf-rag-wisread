package com.wisread.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 创建会话请求 DTO（CreateConversationRequest）。
 * 用于新建一个对话会话。
 */
public record CreateConversationRequest(
        // 会话所属的项目 ID；不能为空
        @NotNull Long projectId,
        // 会话标题（可选）
        String title,
        // 绑定的独立知识库 ID（可选）；为空时不绑定，问答沿用项目检索链路
        Long knowledgeBaseId
) {
    /**
     * 兼容旧的两参构造：不绑定知识库（knowledgeBaseId 为 null）。
     */
    public CreateConversationRequest(Long projectId, String title) {
        this(projectId, title, null);
    }
}
