package com.wisread.service.impl;

import com.wisread.model.KnowledgeChunkSearchResult;
import com.wisread.model.KnowledgeChunkSourceView;
import com.wisread.model.KnowledgeChunkView;
import com.wisread.model.TextChunk;
import com.wisread.service.KnowledgeVectorIndexingService;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 知识库向量索引服务的实现（基于 PostgreSQL + pgvector，使用 JdbcTemplate 直接操作）。
 * 实现要点（与项目链路的 VectorIndexingServiceImpl 同一套技术模式，但表与代码完全独立）：
 *  - 入库：逐条 INSERT 知识库文档切片及其向量，另写 knowledge_base_id 冗余列供知识库级过滤与统计；
 *  - 检索：用 pgvector 的 {@code <=>} 余弦距离算子排序，配合“用户 + 知识库 + 文档 INDEXED”三重隔离，
 *    仅召回当前用户、当前知识库下、已索引完成的文档片段，最后 LIMIT 取 Top-N；
 *  - 向量以 PGobject 的 vector 字面量形式绑定，避免字符串拼接注入风险。
 */
@Service
public class KnowledgeVectorIndexingServiceImpl implements KnowledgeVectorIndexingService {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeVectorIndexingServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存分块文本与向量到 knowledge_chunks。
     * 为什么逐条 INSERT（而非批量）：当前 chunk 数量通常不大，且每条需携带各自的
     * chunk_index / 页码 / token 数等字段，逐条写入逻辑最简单可靠。
     */
    @Override
    @Transactional
    public void saveChunks(
            Long knowledgeBaseId,
            Long documentId,
            Long userId,
            List<TextChunk> chunks,
            List<float[]> embeddings,
            String modelVersion
    ) {
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            jdbcTemplate.update(
                    """
                    INSERT INTO knowledge_chunks
                        (document_id, knowledge_base_id, user_id, chunk_index, content,
                         page_start, page_end, location_label, token_count, embedding, embedding_model_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    documentId,
                    knowledgeBaseId,
                    userId,
                    i,
                    chunk.content(),
                    chunk.pageStart(),
                    chunk.pageEnd(),
                    chunk.locationLabel(),
                    chunk.tokenCount(),
                    toVector(embeddings.get(i)),
                    modelVersion
            );
        }
    }

    /**
     * 删除某知识库文档下全部 chunk（物理删除），用于文档删除或重新解析时清理旧向量。
     */
    @Override
    @Transactional
    public void deleteByDocumentId(Long documentId) {
        jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE document_id = ?", documentId);
    }

    /**
     * 知识库级多文档检索：在知识库下所有 INDEXED 文档的 chunks 中检索。
     * 三重隔离含义：
     *  - kc.user_id = ?           只检索当前用户的数据（多租户隔离）；
     *  - kc.knowledge_base_id = ? 只检索指定知识库下的切片（冗余列直接过滤，无需再 JOIN 知识库表）；
     *  - kd.status = 'INDEXED'    仅检索已成功索引的知识库文档，跳过处理中/失败文档。
     * 距离用 <=> 余弦距离（越小越相似），按距离升序取 LIMIT 个最相关片段。
     */
    @Override
    public List<KnowledgeChunkSearchResult> searchKnowledgeChunks(
            Long userId,
            Long knowledgeBaseId,
            float[] queryEmbedding,
            int topK
    ) {
        String sql = """
                SELECT kc.id, kc.document_id, kd.filename AS document_filename, kc.content,
                       kc.page_start, kc.page_end, kc.location_label,
                       kc.embedding <=> CAST(? AS vector) AS distance
                FROM knowledge_chunks kc
                JOIN knowledge_documents kd ON kd.id = kc.document_id
                WHERE kc.user_id = ?
                  AND kc.knowledge_base_id = ?
                  AND kd.status = 'INDEXED'
                ORDER BY distance
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                (resultSet, rowNum) -> new KnowledgeChunkSearchResult(
                        resultSet.getLong("id"),
                        resultSet.getLong("document_id"),
                        resultSet.getString("document_filename"),
                        resultSet.getString("content"),
                        resultSet.getInt("page_start"),
                        resultSet.getInt("page_end"),
                        resultSet.getString("location_label"),
                        resultSet.getDouble("distance")
                ),
                // 查询向量同样需转成 pgvector 字面量再绑定
                toVector(queryEmbedding),
                userId,
                knowledgeBaseId,
                topK
        );
    }

    /**
     * 按切片 ID 批量查询切片引用视图：JOIN knowledge_documents 带出文件名，
     * WHERE 强制 kc.user_id = ? 保证越权用户的切片不可见；
     * IN 占位符按入参个数动态拼接（只拼占位符，值仍走参数绑定，无注入风险），
     * 不查 embedding 列（回看引用场景无需向量）。
     */
    @Override
    public List<KnowledgeChunkSourceView> findViewsByIds(Long userId, Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        String placeholders = chunkIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        String sql = """
                SELECT kc.id, kc.document_id, kd.filename AS document_filename, kc.content,
                       kc.page_start, kc.page_end, kc.location_label
                FROM knowledge_chunks kc
                JOIN knowledge_documents kd ON kd.id = kc.document_id
                WHERE kc.user_id = ?
                  AND kc.id IN (%s)
                """.formatted(placeholders);
        Object[] parameters = new Object[chunkIds.size() + 1];
        parameters[0] = userId;
        int index = 1;
        for (Long chunkId : chunkIds) {
            parameters[index++] = chunkId;
        }
        return jdbcTemplate.query(
                sql,
                (resultSet, rowNum) -> new KnowledgeChunkSourceView(
                        resultSet.getLong("id"),
                        resultSet.getLong("document_id"),
                        resultSet.getString("document_filename"),
                        resultSet.getString("content"),
                        resultSet.getInt("page_start"),
                        resultSet.getInt("page_end"),
                        resultSet.getString("location_label")
                ),
                parameters
        );
    }

    /**
     * 分页列出某文档的切片明细：按 chunk_index 升序，LIMIT/OFFSET 分页，
     * 不查询 embedding 列（向量体积大且分页展示场景无需返回）。
     */
    @Override
    public List<KnowledgeChunkView> listChunksByDocumentId(Long documentId, int offset, int limit) {
        String sql = """
                SELECT chunk_index, content, page_start, page_end, location_label, token_count
                FROM knowledge_chunks
                WHERE document_id = ?
                ORDER BY chunk_index
                LIMIT ? OFFSET ?
                """;
        return jdbcTemplate.query(
                sql,
                (resultSet, rowNum) -> new KnowledgeChunkView(
                        resultSet.getInt("chunk_index"),
                        resultSet.getString("content"),
                        resultSet.getInt("page_start"),
                        resultSet.getInt("page_end"),
                        resultSet.getString("location_label"),
                        resultSet.getInt("token_count")
                ),
                documentId,
                limit,
                offset
        );
    }

    /**
     * 统计某文档下的切片总数，供分页接口返回 total。
     */
    @Override
    public long countByDocumentId(Long documentId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunks WHERE document_id = ?",
                Long.class,
                documentId
        );
        return count != null ? count : 0L;
    }

    /**
     * 将 float[] 转换为 pgvector 可识别的向量字面量。
     * 为什么用 PGobject：把 [x,y,z] 字符串声明为 SQL 类型 "vector" 后交由驱动绑定，
     * 既避免手写拼接带来的 SQL 注入风险，也确保类型正确映射到 pgvector 列。
     */
    private PGobject toVector(float[] values) {
        String literal = IntStream.range(0, values.length)
                .mapToObj(index -> String.valueOf(values[index]))
                // 用逗号连接并包裹为 [..] 字面量，符合 pgvector 文本格式
                .collect(Collectors.joining(",", "[", "]"));
        try {
            PGobject vector = new PGobject();
            vector.setType("vector");
            vector.setValue(literal);
            return vector;
        } catch (SQLException exception) {
            throw new IllegalStateException("构建 pgvector 向量字面量失败", exception);
        }
    }
}
