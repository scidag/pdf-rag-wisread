package com.wisread.dto;

import java.time.Instant;

public record DocumentResponse(
        Long id,
        String filename,
        Long fileSize,
        Integer pageCount,
        Integer tokenCount,
        String status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
}
