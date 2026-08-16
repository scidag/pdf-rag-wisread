package com.wisread.service;

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

@Service
public class VectorIndexingService {

    private final JdbcTemplate jdbcTemplate;

    public VectorIndexingService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

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

    @Transactional
    public void deleteByDocumentId(Long documentId) {
        jdbcTemplate.update("DELETE FROM document_chunks WHERE document_id = ?", documentId);
    }

    /**
     * 项目级多文档检索：在项目下所有 READY 文档的 chunks 中检索。
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
                toVector(queryEmbedding),
                userId,
                projectId,
                limit
        );
    }

    private PGobject toVector(float[] values) {
        String literal = IntStream.range(0, values.length)
                .mapToObj(index -> String.valueOf(values[index]))
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
