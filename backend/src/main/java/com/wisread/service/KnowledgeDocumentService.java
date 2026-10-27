package com.wisread.service;

import com.wisread.dto.KnowledgeChunkPageResponse;
import com.wisread.dto.KnowledgeDocumentBatchItemResponse;
import com.wisread.dto.KnowledgeDocumentResponse;
import com.wisread.entity.KnowledgeDocument;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 知识库文档（KnowledgeDocument）业务服务接口。
 * 职责：管理知识库维度文档的上传、批量上传、列表、详情、重新解析、删除
 * 与切片分页查询。所有方法均要求传入 userId 做归属校验（越权统一抛 403“无权访问该知识库”）。
 * 上传成功后由 {@link KnowledgeDocumentProcessingService} 异步完成解析/分块/向量化，
 * 状态机为 UPLOADED → PROCESSING → INDEXED / FAILED。
 */
public interface KnowledgeDocumentService {

    /**
     * 上传单个文档到指定知识库并触发异步处理。
     *
     * @param userId 当前用户 ID
     * @param kbId   知识库 ID（需归属校验；ARCHIVED 状态禁止上传，409）
     * @param file   上传文件（支持 pdf/docx/xlsx/txt/md，≤100MB）
     * @return 已持久化的文档实体（状态 UPLOADED）
     */
    KnowledgeDocument upload(Long userId, Long kbId, MultipartFile file);

    /**
     * 批量上传文档：逐文件调用上传，单文件失败不影响其他文件。
     *
     * @param userId 当前用户 ID
     * @param kbId   知识库 ID
     * @param files  上传文件列表
     * @return 逐文件结果（文件名 + 成功实体或中文失败原因）
     */
    List<KnowledgeDocumentBatchItemResponse> batchUpload(Long userId, Long kbId, List<MultipartFile> files);

    /**
     * 列出指定知识库下的文档（按创建时间倒序）。
     */
    List<KnowledgeDocumentResponse> listByKnowledgeBase(Long userId, Long kbId);

    /**
     * 获取单篇文档详情（用于状态轮询）。
     */
    KnowledgeDocumentResponse get(Long userId, Long documentId);

    /**
     * 打开的文档原始文件流及大小（字节）。
     * 流式读取避免大文件整体载入堆内存；响应写完后由 Spring 框架负责关闭流。
     */
    record DocumentContent(InputStream stream, long size) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }

    /**
     * 打开文档原始文件流（用于前端预览，流式返回不整体载入内存）。
     * 校验归属后从对象存储打开文件流。
     */
    DocumentContent openContent(Long userId, Long documentId);

    /**
     * 重新解析文档：清理旧向量、retryCount 清零、状态复位 UPLOADED 并重新触发异步处理（202 语义）。
     * 文档处于 PROCESSING 时抛 409“文档处理中，请勿重复操作”；ARCHIVED 知识库禁止重新解析（409）。
     */
    void reparse(Long userId, Long documentId);

    /**
     * 删除文档：清理向量与切片记录、删除 MinIO 对象、删除文档记录并刷新知识库派生状态。
     * 删除与查看不受知识库归档限制。
     */
    void delete(Long userId, Long documentId);

    /**
     * 分页列出某文档的切片明细（content/页码/定位标签/token 数，不含向量）。
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数（默认 20，上限 100）
     */
    KnowledgeChunkPageResponse pageChunks(Long userId, Long documentId, int page, int size);
}
