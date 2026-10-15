package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 知识库表（knowledge_bases）的持久化实体。
 * 知识库（KnowledgeBase）是独立于项目的文档容器聚合根，每个知识库归属于一个用户，
 * 可包含多份文档，并自带检索配置（topK、相似度阈值、重排开关）。
 * 支持软删除（通过 deletedAt 标记）；状态机为：
 * DRAFT（草稿）→ PROCESSING（处理中）→ ACTIVE（可用）→ ARCHIVED（归档）。
 */
@TableName("knowledge_bases")
public class KnowledgeBase {

    // 状态机取值：草稿（新建默认）
    public static final String STATUS_DRAFT = "DRAFT";
    // 状态机取值：存在处理中的文档
    public static final String STATUS_PROCESSING = "PROCESSING";
    // 状态机取值：可用（已发布或全部文档索引完成）
    public static final String STATUS_ACTIVE = "ACTIVE";
    // 状态机取值：归档（归档后不参与状态自动派生）
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private Long userId; // 知识库拥有者ID（users.id）

    private String name; // 知识库名称

    private String description; // 知识库描述

    private String status = STATUS_DRAFT; // 状态机：DRAFT(草稿)、PROCESSING(处理中)、ACTIVE(可用)、ARCHIVED(归档)

    private Integer topK = 10; // 检索时召回的文档片段数上限

    private Double similarityThreshold = 0.65; // 检索相似度阈值，低于该阈值的片段不参与召回

    private Boolean rerankEnabled = true; // 是否启用重排（rerank）

    private Integer rerankK = 3; // 重排后保留的片段数

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 创建时间，插入时自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt; // 更新时间，插入与每次更新时自动填充

    private Instant deletedAt; // 软删除时间，非空表示该知识库的逻辑删除时间

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(Double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    public Boolean getRerankEnabled() {
        return rerankEnabled;
    }

    public void setRerankEnabled(Boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
    }

    public Integer getRerankK() {
        return rerankK;
    }

    public void setRerankK(Integer rerankK) {
        this.rerankK = rerankK;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
