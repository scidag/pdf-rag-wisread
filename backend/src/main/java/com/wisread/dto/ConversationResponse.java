package com.wisread.dto;

import java.time.Instant;

public record ConversationResponse(
        Long id,
        Long documentId,
        String title,
        Instant createdAt,
        Instant updatedAt
) {
}
