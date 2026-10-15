package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 知识库文档表（knowledge_documents）的持久化实体。
 * 知识库模块专属的文档记录，独立于项目链路的 {@code Document}：
 * 用户向知识库上传文件后生成一条记录，系统异步完成解析、分块与向量化，
 * 状态机统一为：UPLOADED（已上传）→ PROCESSING（处理中）→ INDEXED（已索引）/ FAILED（失败）。
 * 注意：新知识库模块统一使用 INDEXED 表达成功状态（与 knowledge_documents 表注释一致）。
 */
@TableName("knowledge_documents")
public class KnowledgeDocument {

    // 状态机取值：已上传（待处理）
    public static final String STATUS_UPLOADED = "UPLOADED";
    // 状态机取值：处理中（解析/分块/向量化进行中）
    public static final String STATUS_PROCESSING = "PROCESSING";
    // 状态机取值：已索引（处理成功，可参与检索）
    public static final String STATUS_INDEXED = "INDEXED";
    // 状态机取值：处理失败（保留 errorMessage 供前端展示）
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private Long knowledgeBaseId; // 所属知识库ID（knowledge_bases.id）

    private Long userId; // 上传用户ID（users.id），用于数据隔离

    private String filename; // 原始文件名

    private String fileKey; // 对象存储（MinIO）中的文件唯一键

    private Long fileSize; // 文件大小，单位字节

    private String fileType; // 文档类型标识，如 pdf/docx/xlsx/txt/md

    private Integer pageCount; // 文档定位单位总数（PDF 为页数等），解析后填充

    private Integer tokenCount; // 文档折算的 token 总数，用于计量与成本估算

    private String status = STATUS_UPLOADED; // 处理状态机：UPLOADED/PROCESSING/INDEXED/FAILED，默认已上传

    private Integer retryCount = 0; // 处理失败后的已重试次数（首次失败自动重试 1 次）

    private String errorMessage; // 处理失败时记录的错误信息，成功时为 null

    private String embeddingModelVersion; // 生成向量时使用的嵌入模型版本，便于后续模型升级时回溯

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 创建时间，插入时自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt; // 更新时间，插入与每次更新时自动填充

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
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

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
