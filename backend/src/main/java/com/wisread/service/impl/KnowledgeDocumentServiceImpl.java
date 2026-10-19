package com.wisread.service.impl;

import com.wisread.dto.KnowledgeChunkPageResponse;
import com.wisread.dto.KnowledgeChunkResponse;
import com.wisread.dto.KnowledgeDocumentBatchItemResponse;
import com.wisread.dto.KnowledgeDocumentResponse;
import com.wisread.entity.KnowledgeBase;
import com.wisread.entity.KnowledgeDocument;
import com.wisread.exception.ApiException;
import com.wisread.model.DocumentType;
import com.wisread.model.KnowledgeChunkView;
import com.wisread.repository.KnowledgeDocumentRepository;
import com.wisread.service.KnowledgeBaseService;
import com.wisread.service.KnowledgeDocumentProcessingService;
import com.wisread.service.KnowledgeDocumentService;
import com.wisread.service.KnowledgeVectorIndexingService;
import com.wisread.service.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * 知识库文档服务实现（KnowledgeDocumentServiceImpl）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>上传入口：归属校验 → 库状态校验（ARCHIVED 禁止上传）→ 文件类型/大小/文件头校验 →
 *       MinIO 存储（key 前缀 knowledge/，含 userId/kbId/uuid 防重名）→ 落库 UPLOADED →
 *       触发 {@link KnowledgeDocumentProcessingService} 异步处理。</li>
 *   <li>业务约束：支持 pdf/docx/xlsx/txt/md，单文件 ≤100MB，校验与错误消息全部中文。</li>
 *   <li>删除：同时清理 knowledge_chunks 向量与 MinIO 对象，避免孤儿数据；删除与查看不受归档限制。</li>
 * </ul>
 */
@Service
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentServiceImpl.class);

    // 单文件大小上限：100MB，超出返回 413
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    // 切片分页默认每页条数
    private static final int DEFAULT_CHUNK_PAGE_SIZE = 20;
    // 切片分页每页条数上限
    private static final int MAX_CHUNK_PAGE_SIZE = 100;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeVectorIndexingService knowledgeVectorIndexingService;
    private final MinioStorageService minioStorageService;
    private final KnowledgeDocumentProcessingService knowledgeDocumentProcessingService;

    public KnowledgeDocumentServiceImpl(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            KnowledgeVectorIndexingService knowledgeVectorIndexingService,
            MinioStorageService minioStorageService,
            KnowledgeDocumentProcessingService knowledgeDocumentProcessingService
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeVectorIndexingService = knowledgeVectorIndexingService;
        this.minioStorageService = minioStorageService;
        this.knowledgeDocumentProcessingService = knowledgeDocumentProcessingService;
    }

    /**
     * 上传文档（知识库知识入库的入口）。
     *
     * <p>流程：归属校验 → 归档状态校验 → 文件非空/大小/扩展名/文件头校验 →
     * 存入 MinIO → 落库 KnowledgeDocument(UPLOADED) → 刷新知识库派生状态 → 触发异步处理。
     */
    @Override
    @Transactional
    public KnowledgeDocument upload(Long userId, Long kbId, MultipartFile file) {
        // 归属校验：越权或不存在统一 403
        KnowledgeBase knowledgeBase = knowledgeBaseService.findOwnedKnowledgeBase(userId, kbId);
        // 归档知识库禁止上传新文档
        if (KnowledgeBase.STATUS_ARCHIVED.equals(knowledgeBase.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "知识库已归档，禁止上传文档");
        }

        // 文件必须存在
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "上传文件不能为空");
        }
        // 文件大小上限
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小超过 100MB 限制");
        }

        // 先按扩展名识别支持的文档类型，避免把不支持的压缩包读进内存
        DocumentType documentType = DocumentType.fromFilename(file.getOriginalFilename());
        if (documentType == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "不支持的文档类型，仅支持 pdf、docx、xlsx、txt、md");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "读取上传文件失败");
        }
        // 再校验真实文件头，避免伪装扩展名绕过校验
        if (!matchesMagic(bytes, documentType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "文件内容与 ." + documentType.value() + " 扩展名不符");
        }

        // 用 knowledge/userId/kbId/uuid 生成唯一对象键，避免文件名冲突
        String fileKey = "knowledge/" + userId + "/" + kbId + "/" + UUID.randomUUID() + documentType.extension();
        minioStorageService.putObject(fileKey, bytes, documentType.contentType());

        // 创建知识库文档记录，初始状态 UPLOADED（待处理）
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKnowledgeBaseId(kbId);
        document.setUserId(userId);
        document.setFilename(file.getOriginalFilename());
        document.setFileKey(fileKey);
        document.setFileSize((long) bytes.length);
        document.setFileType(documentType.value());
        document.setStatus(KnowledgeDocument.STATUS_UPLOADED);
        knowledgeDocumentRepository.insert(document);

        // 立即刷新派生状态：存在 UPLOADED 文档时知识库状态派生为 PROCESSING
        knowledgeBaseService.refreshDerivedStatus(kbId);

        // 事务提交后再触发异步处理：若在事务内直接触发，异步线程可能先于提交执行
        // findById 读不到未提交的文档行而静默退出，导致文档永久停留在 UPLOADED
        triggerProcessingAfterCommit(document.getId(), userId);
        return document;
    }

    /**
     * 批量上传：逐文件调用上传，单文件失败不影响其他文件，
     * 返回逐文件结果（文件名 + 成功实体或中文失败原因）。
     */
    @Override
    public List<KnowledgeDocumentBatchItemResponse> batchUpload(Long userId, Long kbId, List<MultipartFile> files) {
        List<KnowledgeDocumentBatchItemResponse> results = new ArrayList<>();
        for (MultipartFile file : files) {
            String filename = file != null ? file.getOriginalFilename() : null;
            try {
                KnowledgeDocument document = upload(userId, kbId, file);
                results.add(new KnowledgeDocumentBatchItemResponse(filename, true, toResponse(document), null));
            } catch (ApiException exception) {
                // 单文件失败（类型不支持、超限、归档等）仅记录原因，继续处理后续文件
                results.add(new KnowledgeDocumentBatchItemResponse(filename, false, null, exception.getMessage()));
            }
        }
        return results;
    }

    /**
     * 列出某知识库下的文档（按创建时间倒序）。
     */
    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeDocumentResponse> listByKnowledgeBase(Long userId, Long kbId) {
        knowledgeBaseService.findOwnedKnowledgeBase(userId, kbId);
        return knowledgeDocumentRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 获取单篇文档详情（用于状态轮询），带归属校验。
     */
    @Override
    @Transactional(readOnly = true)
    public KnowledgeDocumentResponse get(Long userId, Long documentId) {
        return toResponse(findOwnedDocument(userId, documentId));
    }

    /**
     * 重新解析文档。
     *
     * <p>约束：文档处于 PROCESSING 时拒绝（409“文档处理中，请勿重复操作”）；
     * ARCHIVED 知识库禁止重新解析（409）。执行：清理旧向量 → retryCount 清零 →
     * 状态复位 UPLOADED → 刷新派生状态 → 重新触发异步处理（202 语义）。
     */
    @Override
    @Transactional
    public void reparse(Long userId, Long documentId) {
        KnowledgeDocument document = findOwnedDocument(userId, documentId);
        if (KnowledgeDocument.STATUS_PROCESSING.equals(document.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "文档处理中，请勿重复操作");
        }
        KnowledgeBase knowledgeBase = knowledgeBaseService.findOwnedKnowledgeBase(userId, document.getKnowledgeBaseId());
        if (KnowledgeBase.STATUS_ARCHIVED.equals(knowledgeBase.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "知识库已归档，禁止重新解析文档");
        }
        // 清理旧向量与切片，避免新旧数据混存
        knowledgeVectorIndexingService.deleteByDocumentId(documentId);
        document.setRetryCount(0);
        document.setStatus(KnowledgeDocument.STATUS_UPLOADED);
        document.setErrorMessage(null);
        knowledgeDocumentRepository.updateById(document);
        knowledgeBaseService.refreshDerivedStatus(document.getKnowledgeBaseId());
        // 与 upload 一致：事务提交后再触发，避免异步线程读不到未提交的状态复位
        triggerProcessingAfterCommit(documentId, userId);
    }

    /**
     * 在当前事务提交后再触发异步处理。
     * 为什么：upload/reparse 均为 @Transactional，若在事务内直接调用 @Async 方法，
     * 异步线程可能在提交前执行 findById 而读不到行（静默放弃），文档将永久停留在 UPLOADED；
     * 无事务上下文时（如批量上传内部调用，self-invocation 事务不生效）直接提交。
     */
    private void triggerProcessingAfterCommit(Long documentId, Long userId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            submitProcessing(documentId, userId);
                        }
                    });
        } else {
            submitProcessing(documentId, userId);
        }
    }

    /**
     * 提交异步处理任务；线程池满被拒绝时保持 UPLOADED，由定时恢复任务兜底重试。
     */
    private void submitProcessing(Long documentId, Long userId) {
        try {
            knowledgeDocumentProcessingService.processDocument(documentId, userId);
        } catch (RejectedExecutionException exception) {
            log.warn("知识库文档 {} 处理任务提交被拒绝，保持 UPLOADED 等待定时恢复重试",
                    documentId, exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] getContent(Long userId, Long documentId) {
        // 归属校验后从对象存储读取原始字节，供前端内联预览
        return minioStorageService.getObject(findOwnedDocument(userId, documentId).getFileKey());
    }

    /**
     * 删除文档：清理向量与切片记录 → 删除 MinIO 对象 → 删除文档记录 → 刷新派生状态。
     * 删除不受知识库归档限制。
     */
    @Override
    @Transactional
    public void delete(Long userId, Long documentId) {
        KnowledgeDocument document = findOwnedDocument(userId, documentId);
        // 先清理向量与切片数据
        knowledgeVectorIndexingService.deleteByDocumentId(documentId);
        // 再删除对象存储中的实际文件
        minioStorageService.deleteObject(document.getFileKey());
        knowledgeDocumentRepository.deleteById(documentId);
        // 删除后文档数/状态分布变化，刷新知识库派生状态
        knowledgeBaseService.refreshDerivedStatus(document.getKnowledgeBaseId());
    }

    /**
     * 分页列出某文档的切片明细：页码从 1 开始，size 默认 20、上限 100，
     * 返回 items + total，供前端分页展示切片内容。
     */
    @Override
    @Transactional(readOnly = true)
    public KnowledgeChunkPageResponse pageChunks(Long userId, Long documentId, int page, int size) {
        // 归属校验：文档不属于该用户统一 403
        findOwnedDocument(userId, documentId);
        int safePage = Math.max(page, 1);
        int safeSize = size <= 0 ? DEFAULT_CHUNK_PAGE_SIZE : Math.min(size, MAX_CHUNK_PAGE_SIZE);
        long total = knowledgeVectorIndexingService.countByDocumentId(documentId);
        List<KnowledgeChunkResponse> items = knowledgeVectorIndexingService
                .listChunksByDocumentId(documentId, (safePage - 1) * safeSize, safeSize)
                .stream()
                .map(this::toChunkResponse)
                .toList();
        return new KnowledgeChunkPageResponse(items, total, safePage, safeSize);
    }

    /**
     * 校验知识库文档归属当前用户，防止越权访问；不存在或不属于该用户统一抛 403。
     */
    private KnowledgeDocument findOwnedDocument(Long userId, Long documentId) {
        return knowledgeDocumentRepository.findByUserIdAndId(userId, documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "无权访问该知识库"));
    }

    /**
     * 校验文件头与扩展名是否一致。PDF 以 %PDF 开头，DOCX/XLSX 以 ZIP 的 PK 魔数开头；
     * TXT/MD 没有固定魔数，按已识别的扩展名放行。
     */
    private boolean matchesMagic(byte[] bytes, DocumentType type) {
        if (type == DocumentType.PDF) {
            return bytes.length >= 4
                    && bytes[0] == '%'
                    && bytes[1] == 'P'
                    && bytes[2] == 'D'
                    && bytes[3] == 'F';
        }
        if (type == DocumentType.DOCX || type == DocumentType.XLSX) {
            return bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K';
        }
        return true;
    }

    private KnowledgeDocumentResponse toResponse(KnowledgeDocument document) {
        return new KnowledgeDocumentResponse(
                document.getId(),
                document.getKnowledgeBaseId(),
                document.getFilename(),
                document.getFileType(),
                document.getFileSize(),
                document.getPageCount(),
                document.getTokenCount(),
                document.getStatus(),
                document.getErrorMessage(),
                document.getEmbeddingModelVersion(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }

    private KnowledgeChunkResponse toChunkResponse(KnowledgeChunkView view) {
        return new KnowledgeChunkResponse(
                view.chunkIndex(),
                view.content(),
                view.pageStart(),
                view.pageEnd(),
                view.locationLabel(),
                view.tokenCount()
        );
    }
}
