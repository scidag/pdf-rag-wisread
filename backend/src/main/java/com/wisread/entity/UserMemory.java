package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 长期用户记忆表（user_memory）的持久化实体。
 * 存储跨会话、跨项目的用户画像/偏好/事实记忆；embedding 向量列不经由本实体映射，
 * 由 {@code UserMemoryServiceImpl} 通过 JdbcTemplate + pgvector 字面量写入与检索
 * （对齐 document_chunks 的处理方式）。
 *
 * <p>status 三态：ACTIVE=生效 / SUPERSEDED=被新记忆替代 / DELETED=删除（用户删或超限淘汰）。
 */
@TableName("user_memory")
public class UserMemory {

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private Long userId; // 归属用户ID（users.id），所有查询必须带此条件

    private String content; // 记忆内容（独立完整、脱离上下文可理解的一句话）

    private String category; // FACT=事实偏好 / GOAL=目标任务 / CONTEXT=背景约束

    private Integer importance; // 重要性 1-5，注入排序与淘汰依据

    private String status; // ACTIVE / SUPERSEDED / DELETED

    private Long sourceConversationId; // 记忆来源会话，仅溯源用，可空

    private String embeddingModelVersion; // 生成向量所用模型版本（模型变更需重建向量）

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 创建时间，插入时自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt; // 更新时间

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getContent() {
        return content;
    }

    public String getCategory() {
        return category;
    }

    public Integer getImportance() {
        return importance;
    }

    public String getStatus() {
        return status;
    }

    public Long getSourceConversationId() {
        return sourceConversationId;
    }

    public String getEmbeddingModelVersion() {
        return embeddingModelVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setImportance(Integer importance) {
        this.importance = importance;
    }

    public void setSourceConversationId(Long sourceConversationId) {
        this.sourceConversationId = sourceConversationId;
    }

    public void setEmbeddingModelVersion(String embeddingModelVersion) {
        this.embeddingModelVersion = embeddingModelVersion;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
