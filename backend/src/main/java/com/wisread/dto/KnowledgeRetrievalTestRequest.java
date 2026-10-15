package com.wisread.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 知识库检索测试请求 DTO（KnowledgeRetrievalTestRequest）。
 * 用于对指定知识库执行一次"改写→召回→重排→生成"全链路测试。
 */
public record KnowledgeRetrievalTestRequest(
        // 测试问题；必填
        @NotBlank(message = "问题不能为空")
        String question
) {
}
