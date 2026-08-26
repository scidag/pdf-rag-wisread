package com.wisread.service;

import com.wisread.model.ChunkSearchResult;
import com.wisread.model.TextChunk;

import java.util.List;

public interface VectorIndexingService {

    void saveChunks(
            Long documentId,
            Long userId,
            List<TextChunk> chunks,
            List<float[]> embeddings,
            String modelVersion
    );

    void deleteByDocumentId(Long documentId);

    List<ChunkSearchResult> searchWithContent(
            Long userId,
            Long projectId,
            float[] queryEmbedding,
            int limit
    );
}
