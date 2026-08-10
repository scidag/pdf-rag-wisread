package com.wisread.dto;

import jakarta.validation.constraints.NotNull;

public record CreateConversationRequest(
        @NotNull Long documentId,
        String title
) {
}
