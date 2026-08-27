package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 文档表（documents）的持久化实体。
 * 用户在“智阅”中上传的文档（如 PDF）即为一条 Document 记录，
 * 系统会对其进行解析、分块、向量化，并跟踪整个索引处理流程的状态。
 */
@TableName("documents")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private Long userId; // 上传用户ID（users.id）

    private Long projectId; // 所属项目ID（projects.id），文档归入的知识库项目

    private String filename; // 原始文件名

    private String fileKey; // 对象存储（如 OSS/S3）中的文件唯一键，用于定位实际文件

    private Long fileSize; // 文件大小，单位字节

    private Integer pageCount; // 文档总页数，解析后填充

    private Integer tokenCount; // 文档折算的 token 总数，用于计量与成本估算

    private String status = "UPLOADED"; // 处理状态机：UPLOADED(已上传)、PROCESSING(处理中)、INDEXED(已索引/可用)、FAILED(失败)

    private Integer retryCount = 0; // 索引处理失败后的重试次数

    private String errorMessage; // 处理失败时记录的错误信息，成功时为 null

    private String embeddingModelVersion; // 生成向量时使用的嵌入模型版本，便于后续模型升级时回溯

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 创建时间，插入时自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt; // 更新时间，插入与每次更新时自动填充

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getFileKey() {
        return fileKey;
    }

    public void setFileKey(String fileKey) {
        this.fileKey = fileKey;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
