package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 文档处理任务表（document_jobs）的持久化实体。
 * 文档上传后，后台会创建异步处理任务（如 PDF 解析与向量索引），
 * 该实体用于跟踪每个任务的状态、重试次数以及起止时间，支撑任务调度与失败重试。
 */
@TableName("document_jobs")
public class DocumentJob {

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private Long documentId; // 关联文档ID（documents.id），本任务要处理的文档

    private String jobType = "PDF_INDEX"; // 任务类型，默认 PDF_INDEX 表示 PDF 解析与向量索引任务

    private String status = "PENDING"; // 任务状态机：PENDING(排队中)、RUNNING(执行中)、DONE(完成)、FAILED(失败)

    private Integer attempt = 0; // 已尝试执行次数，用于实现重试逻辑

    private String errorMessage; // 任务失败时的错误描述，成功时为 null

    private Instant startedAt; // 任务实际开始执行的时间

    private Instant finishedAt; // 任务结束（成功或失败）的时间

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 创建时间，插入时自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt; // 更新时间，插入与每次更新时自动填充

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
