package com.wisread.dto;

/**
 * 创建项目请求 DTO（CreateProjectRequest）。
 * 用于新建一个知识库项目。
 */
public record CreateProjectRequest(
        // 项目名称
        String name,
        // 项目描述（可选）
        String description
) {
}
