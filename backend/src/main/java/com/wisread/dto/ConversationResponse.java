package com.wisread.dto;

import java.time.Instant;

public record ConversationResponse(
        Long id,
        Long projectId,
        String title,
        Instant createdAt,
        Instant updatedAt
) {
}
