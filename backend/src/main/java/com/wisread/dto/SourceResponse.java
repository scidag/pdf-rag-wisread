package com.wisread.dto;

public record SourceResponse(
        int index,
        Long chunkId,
        Long documentId,
        String filename,
        int pageStart,
        int pageEnd,
        String snippet
) {
}
