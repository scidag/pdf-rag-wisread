package com.wisread.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建知识库请求 DTO（KnowledgeBaseCreateRequest）。
 * 用于新建一个知识库（文档容器），创建后状态为 DRAFT（草稿）。
 */
public record KnowledgeBaseCreateRequest(
        // 知识库名称；必填，长度 1~100
        @NotBlank(message = "知识库名称不能为空")
        @Size(max = 100, message = "知识库名称长度需为 1~100")
        String name,
        // 知识库描述（可选）
        String description
) {
}
