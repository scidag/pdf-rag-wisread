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
        String title
) {
}
