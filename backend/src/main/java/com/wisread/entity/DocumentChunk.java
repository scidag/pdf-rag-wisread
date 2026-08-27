package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 文档分块表（document_chunks）的持久化实体。
 * 文档被切片后，每一段文本称为一个分块（Chunk），本实体保存分块的原文内容、
 * 所属页码区间与 token 数。注意：向量（embedding）通常存储于独立的向量库中，
 * 此处通过 embeddingModelVersion 记录生成向量所用的模型，以便检索时对齐。
 */
@TableName("document_chunks")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private Long documentId; // 所属文档ID（documents.id）

    private Long userId; // 所属用户ID（users.id），用于权限隔离与按用户检索

    private Integer chunkIndex; // 分块在文档中的顺序序号，从 0 开始

    private String content; // 分块对应的原文文本内容，即被检索与展示的片段

    private Integer pageStart; // 该分块起始页码（含）

    private Integer pageEnd; // 该分块结束页码（含）

    private Integer tokenCount; // 该分块的 token 数量

    private String embeddingModelVersion; // 生成该分块向量所用的嵌入模型版本

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 创建时间，插入时自动填充

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getPageStart() {
        return pageStart;
    }

    public void setPageStart(Integer pageStart) {
        this.pageStart = pageStart;
    }

    public Integer getPageEnd() {
        return pageEnd;
    }

    public void setPageEnd(Integer pageEnd) {
        this.pageEnd = pageEnd;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public String getEmbeddingModelVersion() {
        return embeddingModelVersion;
    }

    public void setEmbeddingModelVersion(String embeddingModelVersion) {
        this.embeddingModelVersion = embeddingModelVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
