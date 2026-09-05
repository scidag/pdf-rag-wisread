package com.wisread.service;

import com.wisread.dto.DocumentResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档服务接口（DocumentService）。
 *
 * <p>负责“智阅”RAG 系统中文档的上传入口与元数据管理。文档是知识库的载体：
 * 上传后由 {@link DocumentProcessingService} 异步完成解析、分块、向量化并写入 pgvector。
 * 本接口只处理“接收文件 + 落元数据 + 触发处理 + 列表/详情/删除”，不涉及具体的解析流水线。
 */
public interface DocumentService {

    /**
     * 上传文档。
     *
     * <p>做什么：做项目归属与文件校验（非空、≤100MB、PDF），保存到 MinIO，
     * 创建 {@code UPLOADED} 状态文档记录与 {@code PENDING} 处理任务，并触发异步处理流水线。
     *
     * @param userId    当前用户 ID
     * @param projectId 所属项目 ID（必填，决定知识归属）
     * @param file      上传的文件（仅支持 PDF）
     * @return 已创建文档的响应（初始状态为 UPLOADED）
     */
    DocumentResponse upload(Long userId, Long projectId, MultipartFile file);

    /**
     * 列出某项目下的文档。
     *
     * @param userId    当前用户 ID
     * @param projectId 项目 ID
     * @return 文档响应列表
     */
    List<DocumentResponse> listByProject(Long userId, Long projectId);

    /**
     * 列出当前用户上传的全部文档（跨项目）。
     *
     * @param userId 当前用户 ID
     * @return 文档响应列表
     */
    List<DocumentResponse> list(Long userId);

    /**
     * 获取单篇文档详情。
     *
     * @param userId      当前用户 ID
     * @param documentId  文档 ID
     * @return 文档响应
     */
    DocumentResponse get(Long userId, Long documentId);

    /**
     * 读取文档原始 PDF 内容（用于预览）。
     *
     * @param userId      当前用户 ID
     * @param documentId  文档 ID
     * @return PDF 文件字节
     */
    byte[] getContent(Long userId, Long documentId);

    /**
     * 删除文档：同时删除 MinIO 对象与文档元数据。
     *
     * @param userId      当前用户 ID
     * @param documentId  文档 ID
     */
    void delete(Long userId, Long documentId);
}
