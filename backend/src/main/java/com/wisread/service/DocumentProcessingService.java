package com.wisread.service;

import com.wisread.entity.Document;
import com.wisread.entity.DocumentJob;

/**
 * 文档处理服务接口（DocumentProcessingService）。
 *
 * <p>负责“智阅”RAG 知识入库的核心异步流水线：把 MinIO 中的 PDF 解析为文本、
 * 分块、向量化并写入 pgvector，同时维护 {@code Document} 与 {@code DocumentJob} 的状态机。
 *
 * <p>状态机：{@code UPLOADED} → {@code PROCESSING}（任务 {@code RUNNING}）→
 * {@code READY}（任务 {@code SUCCEEDED}）或 {@code FAILED}（任务 {@code FAILED}）。
 * 支持一次自动重试（失败后回退到 {@code UPLOADED}/{@code PENDING} 重新处理）。
 */
public interface DocumentProcessingService {

    /**
     * 处理指定文档。
     *
     * <p>实际实现标注 {@code @Async}，在后台线程池中执行完整的解析→分块→向量化→落库流程。
     *
     * @param documentId 待处理文档 ID
     * @param userId     文档所属用户 ID（用于数据隔离与权限）
     */
    void processDocument(Long documentId, Long userId);

    /**
     * 处理失败的统一处理入口（含一次重试逻辑）。
     *
     * @param document  文档实体（状态将被更新为 UPLOADED 重试或 FAILED）
     * @param job       处理任务实体（状态同步更新）
     * @param exception 触发失败的异常
     */
    void handleFailure(Document document, DocumentJob job, Exception exception);
}
