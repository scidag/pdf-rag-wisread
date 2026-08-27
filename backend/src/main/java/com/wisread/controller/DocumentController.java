package com.wisread.controller;

import com.wisread.dto.DocumentResponse;
import com.wisread.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档（Document）控制器。
 * 负责知识库文档相关的 REST 端点：上传文档、列出文档、查看文档、查询处理状态、删除文档。
 * 文档归属于某个项目，所有接口均需登录（从 Authentication 获取当前用户 ID）。
 * 基础路径：/api/v1/documents
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /** POST /api/v1/documents（multipart/form-data）：上传文档接口。
     * 入参：表单字段 file（上传的文件）、projectId（归属项目 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：将文档上传到指定项目，触发解析/向量化等后台处理流程。
     * 返回：201 Created 及文档信息（DocumentResponse，含处理状态）。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("projectId") Long projectId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.upload(userId, projectId, file));
    }

    /** GET /api/v1/documents?projectId=：列出某项目下的文档列表。
     * 入参：必填查询参数 projectId（项目 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：返回该用户在该项目下的全部文档及其处理状态。
     * 返回：200 OK 及文档信息列表（List<DocumentResponse>）。 */
    @GetMapping
    public ResponseEntity<List<DocumentResponse>> list(
            @RequestParam("projectId") Long projectId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(documentService.listByProject(userId, projectId));
    }

    /** GET /api/v1/documents/{documentId}：获取单个文档详情。
     * 入参：路径变量 documentId（文档 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：返回指定文档的完整信息。
     * 返回：200 OK 及文档信息（DocumentResponse）。 */
    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentResponse> get(
            @PathVariable Long documentId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(documentService.get(userId, documentId));
    }

    /** GET /api/v1/documents/{documentId}/status：查询文档处理状态。
     * 入参：路径变量 documentId（文档 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：返回指定文档的当前处理状态（如 PENDING/PROCESSING/DONE/FAILED）及错误信息。
     * 返回：200 OK 及文档信息（DocumentResponse，重点关注 status 与 errorMessage 字段）。 */
    @GetMapping("/{documentId}/status")
    public ResponseEntity<DocumentResponse> status(
            @PathVariable Long documentId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(documentService.get(userId, documentId));
    }

    /** DELETE /api/v1/documents/{documentId}：删除文档接口。
     * 入参：路径变量 documentId（文档 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：从项目中移除指定文档及其相关向量数据。
     * 返回：204 No Content。 */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long documentId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        documentService.delete(userId, documentId);
        return ResponseEntity.noContent().build();
    }
}
