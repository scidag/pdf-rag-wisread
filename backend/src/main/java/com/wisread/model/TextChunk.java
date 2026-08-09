package com.wisread.model;

public record TextChunk(
        String content,
        int pageStart,
        int pageEnd,
        int tokenCount
) {
}
