package com.wisread.dto;

public record SourceResponse(
        int index,
        Long chunkId,
        int pageStart,
        int pageEnd,
        String snippet
) {
}
