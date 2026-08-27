package com.wisread.service.impl;

import com.wisread.service.VectorIndexingService;

import com.wisread.model.TextChunk;
import com.wisread.model.ChunkSearchResult;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 向量索引服务的实现（基于 PostgreSQL + pgvector，使用 JdbcTemplate 直接操作）。
 * 实现要点：
 *  - 入库：逐条 INSERT 文档片段及其向量，向量通过 {@link #toVector} 拼成 [x,y,z] 字面量交给 pgvector；
 *  - 检索：用 pgvector 的 {@code <=>} 余弦距离算子排序，配合“用户 + 项目 + 文档 READY”三重隔离，
 *    仅召回当前用户、当前项目下、已解析完成（READY）的文档片段，最后 LIMIT 取 Top-N。
 */
@Service
public class VectorIndexingServiceImpl implements VectorIndexingService {

    private final JdbcTemplate jdbcTemplate;

    public VectorIndexingServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存分块文本与向量。
     * 为什么逐条 INSERT（而非批量）：当前 chunk 数量通常不大，且每条需携带各自的
     * chunk_index / 页码 / token 数等字段，逐条写入逻辑最简单可靠；向量以 PGobject
     * 的 vector 字面量形式绑定，避免字符串拼接注入风险。
     */
    @Transactional
    public void saveChunks(
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
                    INSERT INTO document_chunks
                        (document_id, user_id, chunk_index, content, page_start, page_end, token_count, embedding, embedding_model_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    documentId,
                    userId,
                    i,
                    chunk.content(),
                    chunk.pageStart(),
                    chunk.pageEnd(),
                    chunk.tokenCount(),
                    toVector(embeddings.get(i)),
                    modelVersion
            );
        }
    }

    /**
     * 删除某文档下全部 chunk（物理删除），用于文档删除或重新解析时清理旧向量。
     */
    @Transactional
    public void deleteByDocumentId(Long documentId) {
        jdbcTemplate.update("DELETE FROM document_chunks WHERE document_id = ?", documentId);
    }

    /**
     * 项目级多文档检索：在项目下所有 READY 文档的 chunks 中检索。
     * 三重隔离含义：
     *  - dc.user_id = ?   只检索当前用户的数据（多租户隔离）；
     *  - d.project_id = ? 只检索指定项目下的文档；
     *  - d.status = 'READY' 仅检索已成功解析入库的文档，跳过解析中/失败文档。
     * 距离用 <=> 余弦距离（越小越相似），按距离升序取 LIMIT 个最相关片段。
     */
    public List<ChunkSearchResult> searchWithContent(Long userId, Long projectId, float[] queryEmbedding, int limit) {
        String sql = """
                SELECT dc.id, dc.document_id, dc.content, dc.page_start, dc.page_end,
                       dc.embedding <=> CAST(? AS vector) AS distance,
                       d.filename AS document_filename
                FROM document_chunks dc
                JOIN documents d ON d.id = dc.document_id
                WHERE dc.user_id = ?
                  AND d.project_id = ?
                  AND d.status = 'READY'
                ORDER BY distance
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                (resultSet, rowNum) -> new ChunkSearchResult(
                        resultSet.getLong("id"),
                        resultSet.getLong("document_id"),
                        resultSet.getString("document_filename"),
                        resultSet.getString("content"),
                        resultSet.getInt("page_start"),
                        resultSet.getInt("page_end"),
                        resultSet.getDouble("distance")
                ),
                // 查询向量同样需转成 pgvector 字面量再绑定
                toVector(queryEmbedding),
                userId,
                projectId,
                limit
        );
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
            throw new IllegalStateException("failed to build pgvector literal", exception);
        }
    }
}
