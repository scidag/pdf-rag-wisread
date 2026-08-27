package com.wisread.dto;

/**
 * 更新项目请求 DTO（UpdateProjectRequest）。
 * 用于修改项目的名称与描述。
 */
public record UpdateProjectRequest(
        // 新的项目名称
        String name,
        // 新的项目描述
        String description
) {
}
