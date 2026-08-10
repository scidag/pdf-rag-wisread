package com.wisread.dto;

import java.time.Instant;
import java.util.List;

public record MessageResponse(
        Long id,
        String role,
        String content,
        List<SourceResponse> sources,
        Instant createdAt
) {
}
