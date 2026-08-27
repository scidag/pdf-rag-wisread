package com.wisread.exception;

/**
 * 统一错误响应体（Record）。
 * 所有异常经 {@link GlobalExceptionHandler} 处理后均返回该结构，
 * 字段 {@code code} 为 HTTP 状态码，{@code message} 为错误信息，保证前端错误格式一致。
 */
public record ErrorResponse(
        int code,
        String message
) {
}
