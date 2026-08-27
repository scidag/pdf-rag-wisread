package com.wisread.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 聊天（问答）请求 DTO（ChatRequest）。
 * 用于向会话发送用户提问，触发基于 RAG 的流式问答。
 */
public record ChatRequest(
        // 用户提问内容；不能为空，且最多 2000 个字符
        @NotBlank @Size(max = 2000) String content
) {
}
