package com.wisread.controller;

import com.wisread.dto.KnowledgeChunkPageResponse;
import com.wisread.dto.KnowledgeDocumentBatchItemResponse;
import com.wisread.dto.KnowledgeDocumentResponse;
import com.wisread.entity.KnowledgeDocument;
import com.wisread.service.KnowledgeDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文档（KnowledgeDocument）控制器。
 * 负责知识库模块文档相关的 REST 端点：上传文档、批量上传、列出文档、查看文档（状态轮询）、
 * 重新解析、删除文档、分页查询切片。文档归属于某个知识库，所有接口均需登录
 * （从 Authentication 获取当前用户 ID），归属校验由服务层完成（越权 403）。
 * 端点分组：
 *  - /api/v1/knowledge-bases/{kbId}/documents：知识库维度（上传/批量/列表）
 *  - /api/v1/knowledge-documents/{id}：文档维度（详情/重新解析/删除/切片分页）
 */
@RestController
public class KnowledgeDocumentController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentController.class);

    private final KnowledgeDocumentService knowledgeDocumentService;

    public KnowledgeDocumentController(KnowledgeDocumentService knowledgeDocumentService) {
        this.knowledgeDocumentService = knowledgeDocumentService;
    }

    /** POST /api/v1/knowledge-bases/{kbId}/documents（multipart/form-data）：上传知识库文档。
     * 入参：路径变量 kbId（知识库 ID）、表单字段 file（上传的文件），当前登录用户由 Authentication 解析得到。
     * 业务含义：将文档上传到指定知识库，触发解析/分块/向量化等后台处理流程。
     * 返回：201 Created 及文档信息（KnowledgeDocumentResponse，含处理状态）。 */
    @PostMapping(value = "/api/v1/knowledge-bases/{kbId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KnowledgeDocumentResponse> upload(
            @PathVariable Long kbId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        KnowledgeDocument document = knowledgeDocumentService.upload(userId, kbId, file);
        log.info("上传知识库文档 userId={} knowledgeBaseId={} documentId={} filename={} size={}B",
                userId, kbId, document.getId(), document.getFilename(), document.getFileSize());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(document));
    }

    /** POST /api/v1/knowledge-bases/{kbId}/documents/batch（multipart/form-data）：批量上传知识库文档。
     * 入参：路径变量 kbId（知识库 ID）、表单字段 files（上传文件列表），当前登录用户由 Authentication 解析得到。
     * 业务含义：批量上传文档到指定知识库，逐文件返回结果，单文件失败不影响其他文件。
     * 返回：200 OK 及逐文件结果列表（List<KnowledgeDocumentBatchItemResponse>）。 */
    @PostMapping(value = "/api/v1/knowledge-bases/{kbId}/documents/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<KnowledgeDocumentBatchItemResponse>> batchUpload(
            @PathVariable Long kbId,
            @RequestParam("files") List<MultipartFile> files,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        log.info("批量上传知识库文档 userId={} knowledgeBaseId={} fileCount={}", userId, kbId, files.size());
        return ResponseEntity.ok(knowledgeDocumentService.batchUpload(userId, kbId, files));
    }

    /** GET /api/v1/knowledge-bases/{kbId}/documents：列出知识库下的文档列表。
     * 入参：路径变量 kbId（知识库 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：返回该知识库下全部文档及其处理状态（按创建时间倒序）。
     * 返回：200 OK 及文档信息列表（List<KnowledgeDocumentResponse>）。 */
    @GetMapping("/api/v1/knowledge-bases/{kbId}/documents")
    public ResponseEntity<List<KnowledgeDocumentResponse>> list(
            @PathVariable Long kbId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(knowledgeDocumentService.listByKnowledgeBase(userId, kbId));
    }

    /** GET /api/v1/knowledge-documents/{id}：获取知识库文档详情（状态轮询）。
     * 入参：路径变量 id（文档 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：返回指定文档的完整信息（重点关注 status 与 errorMessage 字段）。
     * 返回：200 OK 及文档信息（KnowledgeDocumentResponse）。 */
    @GetMapping("/api/v1/knowledge-documents/{id}")
    public ResponseEntity<KnowledgeDocumentResponse> get(
            @PathVariable Long id,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(knowledgeDocumentService.get(userId, id));
    }

    /** POST /api/v1/knowledge-documents/{id}/reparse：重新解析知识库文档。
     * 入参：路径变量 id（文档 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：清理旧向量、retryCount 清零、状态复位 UPLOADED 并重新触发异步处理（202 语义）；
     * 文档处理中时返回 409。
     * 返回：202 Accepted（无响应体）。 */
    @PostMapping("/api/v1/knowledge-documents/{id}/reparse")
    public ResponseEntity<Void> reparse(
            @PathVariable Long id,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        knowledgeDocumentService.reparse(userId, id);
        log.info("重新解析知识库文档 userId={} documentId={}", userId, id);
        return ResponseEntity.accepted().build();
    }

    /** DELETE /api/v1/knowledge-documents/{id}：删除知识库文档。
     * 入参：路径变量 id（文档 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：删除文档及其向量数据与 MinIO 对象，并刷新知识库派生状态。
     * 返回：204 No Content。 */
    @DeleteMapping("/api/v1/knowledge-documents/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        knowledgeDocumentService.delete(userId, id);
        log.info("删除知识库文档 userId={} documentId={}", userId, id);
        return ResponseEntity.noContent().build();
    }

    /** GET /api/v1/knowledge-documents/{id}/chunks?page=&size=：分页查询文档切片。
     * 入参：路径变量 id（文档 ID）、查询参数 page（页码，从 1 开始）与 size（每页条数，默认 20），
     * 当前登录用户由 Authentication 解析得到。
     * 业务含义：分页返回该文档的切片明细（内容/页码/定位标签/token 数，不含向量）。
     * 返回：200 OK 及分页结果（KnowledgeChunkPageResponse，含 items + total）。 */
    @GetMapping("/api/v1/knowledge-documents/{id}/chunks")
    public ResponseEntity<KnowledgeChunkPageResponse> chunks(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(knowledgeDocumentService.pageChunks(userId, id, page, size));
    }

    // 将 KnowledgeDocument 实体转换为 KnowledgeDocumentResponse DTO（对齐 KnowledgeBaseController 风格）
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
}
