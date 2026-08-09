package com.wisread.exception;

public record ErrorResponse(
        int code,
        String message
) {
}
