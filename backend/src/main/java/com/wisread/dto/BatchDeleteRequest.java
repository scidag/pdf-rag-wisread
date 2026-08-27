package com.wisread.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量删除请求 DTO（BatchDeleteRequest）。
 * 用于批量删除项目时传递待删除对象的 ID 列表。
 */
public record BatchDeleteRequest(
        // 待批量删除的项目 ID 列表；不能为空，且最多 100 个，每个 ID 不能为 null
        @NotEmpty(message = "ids is required")
        @Size(max = 100, message = "ids must not exceed 100")
        List<@NotNull(message = "id is required") Long> ids
) {
}
