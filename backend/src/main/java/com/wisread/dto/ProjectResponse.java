package com.wisread.dto;

import java.time.Instant;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        long documentCount,
        long conversationCount,
        Instant createdAt,
        Instant updatedAt
) {
}
