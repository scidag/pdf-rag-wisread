package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 回答引用来源表（answer_sources）的持久化实体。
 * 在 RAG 问答场景中，模型生成的每条回答都可能引用若干文档分块，
 * 该实体用于记录某条回答消息（Message）引用了哪些文档分块（DocumentChunk），
 * 以及它们与问题的相关度，便于前端展示回答的引用出处。
 */
@TableName("answer_sources")
public class AnswerSource {

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private Long messageId; // 关联的对话消息ID（messages.id），即引用该来源的那条回答

    private Long chunkId; // 关联的文档分块ID（document_chunks.id），被引用的具体文本片段

    private Long documentId; // 关联文档ID（documents.id），被引用分块所属的原始文档

    private Float relevanceScore; // 该来源与用户问题的相关度得分，越高表示越相关

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 记录创建时间，插入时由 MyBatis-Plus 自动填充

    public Long getId() {
        return id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Float getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(Float relevanceScore) {
        this.relevanceScore = relevanceScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
