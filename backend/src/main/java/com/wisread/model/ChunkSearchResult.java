package com.wisread.model;

public record ChunkSearchResult(
        Long chunkId,
        String content,
        int pageStart,
        int pageEnd,
        double distance
) {
}
