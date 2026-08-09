package com.wisread.dto;

public record UserResponse(
        Long id,
        String username,
        String email
) {
}
