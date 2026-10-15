package com.wisread.service;

/**
 * 知识库文档处理服务接口。
 * <p>
 * 负责知识库模块的知识入库异步流水线：把 MinIO 中的文档解析为文本、
 * 分块、向量化并写入 knowledge_chunks（pgvector），同时维护
 * {@code KnowledgeDocument} 的状态机。
 * <p>
 * 状态机（与 documents 旧管道不同，新知识库模块统一使用 INDEXED 表达成功）：
 * {@code UPLOADED} → {@code PROCESSING} → {@code INDEXED} 或 {@code FAILED}。
 * 支持一次自动重试（失败后清理已写向量、回退到 UPLOADED 重新处理）。
 */
public interface KnowledgeDocumentProcessingService {

    /**
     * 异步处理指定知识库文档。
     *
     * <p>实际实现标注 {@code @Async("documentTaskExecutor")}，在后台线程池中执行
     * 完整的解析 → 分块 → 向量化 → 落库流程，调用方（上传/重新解析接口）不会阻塞。
     *
     * @param documentId 待处理文档 ID
     * @param userId     文档所属用户 ID（用于数据隔离与 embedding 用量归属）
     */
    void processDocument(Long documentId, Long userId);

    /**
     * 定时恢复卡死任务：把 PROCESSING 状态停留过久（updated_at 过旧）的文档
     * 重新调度处理；已达到重试上限的直接置为 FAILED。
     */
    void recoverStuckJobs();
}
