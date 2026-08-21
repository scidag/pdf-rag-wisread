package com.wisread.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BatchDeleteRequest(
        @NotEmpty(message = "ids is required")
        @Size(max = 100, message = "ids must not exceed 100")
        List<@NotNull(message = "id is required") Long> ids
) {
}
