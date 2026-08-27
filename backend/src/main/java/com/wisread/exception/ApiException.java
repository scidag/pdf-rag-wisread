package com.wisread.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类。
 * 用于在业务层直接抛出并携带 HTTP 状态码，由 {@link GlobalExceptionHandler}
 * 统一转换为规范的错误响应，避免在控制器中手动构造 ResponseEntity。
 */
public class ApiException extends RuntimeException {

    // 该异常对应的 HTTP 响应状态码
    private final HttpStatus status;

    /**
     * 构造业务异常。
     *
     * @param status  希望返回给客户端的 HTTP 状态码
     * @param message 错误描述信息
     */
    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 获取该异常对应的 HTTP 状态码。
     */
    public HttpStatus getStatus() {
        return status;
    }
}
