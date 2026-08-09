package com.wisread.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserResponse user
) {
}
