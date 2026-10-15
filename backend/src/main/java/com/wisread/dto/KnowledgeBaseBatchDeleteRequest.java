package com.wisread.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量删除知识库请求 DTO（KnowledgeBaseBatchDeleteRequest）。
 * 用于批量删除（软删除）知识库时传递待删除对象的 ID 列表。
 */
public record KnowledgeBaseBatchDeleteRequest(
        // 待批量删除的知识库 ID 列表；不能为空，且单次最多 100 个，每个 ID 不能为 null
        @NotEmpty(message = "ids 不能为空")
        @Size(max = 100, message = "单次最多删除 100 个知识库")
        List<@NotNull(message = "ids 不能包含空值") Long> ids
) {
}
