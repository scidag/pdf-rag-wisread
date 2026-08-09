package com.wisread.service;

import com.wisread.model.TextChunk;
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

    public List<Long> search(Long userId, Long documentId, float[] queryEmbedding, int limit) {
        String sql = """
                SELECT id
                FROM document_chunks
                WHERE user_id = ? AND document_id = ?
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT ?
                """;
        return jdbcTemplate.query(
                sql,
                (resultSet, rowNum) -> resultSet.getLong("id"),
                userId,
                documentId,
                toVector(queryEmbedding),
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
