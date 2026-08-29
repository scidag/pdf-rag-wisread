package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 问答日志表（chat_logs）的持久化实体。
 * 每次用户提问记录问题、所用模型、检索到的文本与来源文档，用于审计和复盘。
 */
@TableName("chat_logs")
public class ChatLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String question;

    private String model;

    private String retrievedContent;

    private String documentNames;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getRetrievedContent() {
        return retrievedContent;
    }

    public void setRetrievedContent(String retrievedContent) {
        this.retrievedContent = retrievedContent;
    }

    public String getDocumentNames() {
        return documentNames;
    }

    public void setDocumentNames(String documentNames) {
        this.documentNames = documentNames;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
