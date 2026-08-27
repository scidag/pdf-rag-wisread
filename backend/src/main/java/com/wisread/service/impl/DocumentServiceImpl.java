package com.wisread.service.impl;

import com.wisread.service.DocumentService;
import com.wisread.service.DocumentProcessingService;
import com.wisread.service.MinioStorageService;

import com.wisread.dto.DocumentResponse;
import com.wisread.entity.Document;
import com.wisread.entity.DocumentJob;
import com.wisread.exception.ApiException;
import com.wisread.repository.DocumentJobRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * 文档服务实现（DocumentServiceImpl）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>上传入口：负责参数校验、MinIO 存储、创建 {@code Document}(UPLOADED) 与
 *       {@code DocumentJob}(PENDING) 记录，并同步触发 {@code DocumentProcessingService} 异步处理。</li>
 *   <li>业务约束：项目必填；文件非空、≤100MB、仅 PDF；单项目最多 5 篇文档。</li>
 *   <li>删除：同时清理 MinIO 中的对象与数据库元数据，避免孤儿文件。</li>
 * </ul>
 */
@Service
public class DocumentServiceImpl implements DocumentService {

    // 单文件大小上限：100MB，超出返回 413
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    // 单项目文档数量上限，超出返回 400，控制向量库规模与成本
    private static final int MAX_DOCUMENTS_PER_PROJECT = 5;

    private final DocumentRepository documentRepository;
    private final DocumentJobRepository documentJobRepository;
    private final ProjectRepository projectRepository;
    private final MinioStorageService minioStorageService;
    private final DocumentProcessingService documentProcessingService;

    public DocumentServiceImpl(
            DocumentRepository documentRepository,
            DocumentJobRepository documentJobRepository,
            ProjectRepository projectRepository,
            MinioStorageService minioStorageService,
            DocumentProcessingService documentProcessingService
    ) {
        this.documentRepository = documentRepository;
        this.documentJobRepository = documentJobRepository;
        this.projectRepository = projectRepository;
        this.minioStorageService = minioStorageService;
        this.documentProcessingService = documentProcessingService;
    }

    /**
     * 上传文档（RAG 知识入库的入口）。
     *
     * <p>流程：校验项目与文件 → 读字节 → 校验 PDF → 检查单项目文档数上限 →
     * 存入 MinIO → 落库 Document(UPLOADED) 与 DocumentJob(PENDING) → 触发异步解析流水线。
     */
    @Transactional
    public DocumentResponse upload(Long userId, Long projectId, MultipartFile file) {
        // 项目必填，决定知识归属
        if (projectId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "projectId is required");
        }
        projectRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));

        // 文件必须存在
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file is required");
        }
        // 文件大小上限
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file exceeds 100MB limit");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "failed to read uploaded file");
        }
        // 仅支持真实 PDF（校验文件头 %PDF）
        if (!isPdf(bytes)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "only PDF files are supported");
        }
        // 单项目文档数上限，避免向量库无限膨胀
        if (documentRepository.countByProjectId(projectId) >= MAX_DOCUMENTS_PER_PROJECT) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "project document limit of " + MAX_DOCUMENTS_PER_PROJECT + " reached");
        }

        // 用 userId/uuid 生成唯一对象键，避免文件名冲突
        String fileKey = userId + "/" + UUID.randomUUID() + ".pdf";
        minioStorageService.putObject(fileKey, bytes, "application/pdf");

        // 创建文档记录，初始状态 UPLOADED（待处理）
        Document document = new Document();
        document.setUserId(userId);
        document.setProjectId(projectId);
        document.setFilename(file.getOriginalFilename());
        document.setFileKey(fileKey);
        document.setFileSize((long) bytes.length);
        document.setStatus("UPLOADED");
        documentRepository.insert(document);

        // 创建处理任务，初始状态 PENDING（由异步流水线接管）
        DocumentJob job = new DocumentJob();
        job.setDocumentId(document.getId());
        job.setStatus("PENDING");
        documentJobRepository.insert(job);

        // 同步触发异步处理（@Async 会在线程池后台执行）
        documentProcessingService.processDocument(document.getId(), userId);
        return toResponse(document);
    }

    /**
     * 列出某项目下的文档（按创建时间倒序）。
     */
    @Transactional(readOnly = true)
    public List<DocumentResponse> listByProject(Long userId, Long projectId) {
        projectRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        return documentRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 列出当前用户全部文档（跨项目，按创建时间倒序）。
     */
    @Transactional(readOnly = true)
    public List<DocumentResponse> list(Long userId) {
        return documentRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 获取单篇文档详情。
     */
    @Transactional(readOnly = true)
    public DocumentResponse get(Long userId, Long documentId) {
        return toResponse(findOwnedDocument(userId, documentId));
    }

    /**
     * 删除文档：同时删除 MinIO 中的对象与数据库元数据，避免产生孤儿文件。
     */
    @Transactional
    public void delete(Long userId, Long documentId) {
        Document document = findOwnedDocument(userId, documentId);
        // 先删对象存储中的实际文件
        minioStorageService.deleteObject(document.getFileKey());
        documentRepository.deleteById(document.getId());
    }

    /**
     * 校验文档归属当前用户（及项目），防止越权访问。
     */
    private Document findOwnedDocument(Long userId, Long documentId) {
        Document document = documentRepository.findByUserIdAndId(userId, documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "document not found"));
        if (document.getProjectId() != null) {
            projectRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, document.getProjectId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        }
        return document;
    }

    /**
     * 通过文件头（%PDF）判断是否为 PDF 文件。
     */
    private boolean isPdf(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F';
    }

    private DocumentResponse toResponse(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getProjectId(),
                document.getFilename(),
                document.getFileSize(),
                document.getPageCount(),
                document.getTokenCount(),
                document.getStatus(),
                document.getErrorMessage(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
