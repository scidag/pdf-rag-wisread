package com.wisread.service;

import com.wisread.model.KnowledgeChunkSearchResult;
import com.wisread.model.KnowledgeChunkSourceView;
import com.wisread.model.KnowledgeChunkView;
import com.wisread.model.TextChunk;

import java.util.Collection;
import java.util.List;

/**
 * 知识库向量索引服务接口（基于 PostgreSQL + pgvector）。
 * <p>
 * 负责知识库专属切片表 knowledge_chunks 的写入、清理与检索，
 * 与项目链路的 {@link VectorIndexingService} 完全独立（不共用表与代码路径）。
 * 检索时按 user_id + knowledge_base_id + 文档状态 INDEXED 三重隔离，
 * 使用 pgvector 的 {@code <=>} 余弦距离算子取 Top-N。
 */
public interface KnowledgeVectorIndexingService {

    /**
     * 保存知识库文档的切片文本与向量。
     *
     * @param knowledgeBaseId 所属知识库 id（写入冗余列，便于知识库级统计与检索过滤）
     * @param documentId      所属文档 id
     * @param userId          用户 id（多租户隔离）
     * @param chunks          切片文本列表
     * @param embeddings      与 chunks 一一对应的向量列表
     * @param modelVersion    嵌入模型版本
     */
    void saveChunks(
            Long knowledgeBaseId,
            Long documentId,
            Long userId,
            List<TextChunk> chunks,
            List<float[]> embeddings,
            String modelVersion
    );

    /**
     * 删除某知识库文档下全部切片（物理删除），用于文档删除或重新解析时清理旧向量。
     *
     * @param documentId 文档 id
     */
    void deleteByDocumentId(Long documentId);

    /**
     * 知识库级向量检索：在该知识库下所有 INDEXED 文档的切片中检索 Top-N。
     *
     * @param userId          用户 id（用户隔离）
     * @param knowledgeBaseId 知识库 id
     * @param queryEmbedding  查询向量
     * @param topK            召回片段数上限
     * @return 按余弦距离升序排列的命中结果
     */
    List<KnowledgeChunkSearchResult> searchKnowledgeChunks(
            Long userId,
            Long knowledgeBaseId,
            float[] queryEmbedding,
            int topK
    );

    /**
     * 按切片 ID 批量查询切片引用视图（含文件名/内容/页码定位，不含 embedding 向量）。
     * 强制按 user_id 校验归属：非本人知识库下的切片一律不返回（回看引用时的越权防护）。
     *
     * @param userId   当前用户 id（多租户隔离）
     * @param chunkIds 切片 ID 集合（knowledge_chunks.id）
     * @return 命中的切片视图列表（不存在的切片被静默忽略）
     */
    List<KnowledgeChunkSourceView> findViewsByIds(Long userId, Collection<Long> chunkIds);

    /**
     * 分页列出某文档的切片明细（按 chunk_index 升序，不含 embedding 向量）。
     *
     * @param documentId 文档 id
     * @param offset     偏移量（从 0 开始）
     * @param limit      每页条数
     * @return 当前页切片视图列表
     */
    List<KnowledgeChunkView> listChunksByDocumentId(Long documentId, int offset, int limit);

    /**
     * 统计某文档下的切片总数（供分页返回 total）。
     *
     * @param documentId 文档 id
     * @return 切片总数
     */
    long countByDocumentId(Long documentId);
}
