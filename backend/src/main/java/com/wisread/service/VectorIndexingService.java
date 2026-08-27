package com.wisread.service;

import com.wisread.model.ChunkSearchResult;
import com.wisread.model.TextChunk;

import java.util.List;

/**
 * 向量索引服务接口。
 * 职责：管理文档片段（chunk）在 PostgreSQL + pgvector 中的写入与检索：
 * 入库时把文本与向量落库，检索时按用户/项目/文档状态隔离，用余弦距离召回最相关片段。
 */
public interface VectorIndexingService {

    /**
     * 将分块文本及其对应向量批量写入向量库。
     *
     * @param documentId  所属文档 ID
     * @param userId      所属用户 ID（用于数据隔离）
     * @param chunks      文本块列表（与 embeddings 一一对应）
     * @param embeddings  向量列表（与 chunks 一一对应）
     * @param modelVersion 生成这些向量所用的 Embedding 模型版本，便于后续模型升级时区分旧向量
     */
    void saveChunks(
            Long documentId,
            Long userId,
            List<TextChunk> chunks,
            List<float[]> embeddings,
            String modelVersion
    );

    /**
     * 删除某文档下的全部 chunk（文档删除或重新解析时级联清理向量数据）。
     *
     * @param documentId 文档 ID
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 在指定项目下执行带内容的向量相似度检索。
     *
     * @param userId          用户 ID，限定只检索该用户的数据
     * @param projectId       项目 ID，限定只检索该项目下文档
     * @param queryEmbedding  查询语句的向量
     * @param limit           返回的最大结果数
     * @return 命中的文档片段（含内容、页码、来源文件名、距离）
     */
    List<ChunkSearchResult> searchWithContent(
            Long userId,
            Long projectId,
            float[] queryEmbedding,
            int limit
    );
}
