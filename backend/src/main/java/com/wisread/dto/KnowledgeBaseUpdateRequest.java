package com.wisread.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 更新知识库请求 DTO（KnowledgeBaseUpdateRequest）。
 * 用于修改知识库的名称、描述与检索配置；仅覆盖请求中提供的字段（null 表示不修改）。
 */
public record KnowledgeBaseUpdateRequest(
        // 新的知识库名称；提供时长度 1~100
        @Size(max = 100, message = "知识库名称长度需为 1~100")
        String name,
        // 新的知识库描述
        String description,
        // 检索召回片段数上限
        @Min(value = 1, message = "topK 最小为 1")
        @Max(value = 100, message = "topK 最大为 100")
        Integer topK,
        // 检索相似度阈值，取值 0~1
        @DecimalMin(value = "0.0", message = "similarityThreshold 需在 0~1 之间")
        @DecimalMax(value = "1.0", message = "similarityThreshold 需在 0~1 之间")
        Double similarityThreshold,
        // 是否启用重排（rerank）
        Boolean rerankEnabled,
        // 重排后保留的片段数
        @Min(value = 1, message = "rerankK 最小为 1")
        Integer rerankK
) {
}
