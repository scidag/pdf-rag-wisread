package com.wisread.model;

public record ChunkSearchResult(
        Long chunkId,
        Long documentId,
        String filename,
        String content,
        int pageStart,
        int pageEnd,
        double distance
) {
}
