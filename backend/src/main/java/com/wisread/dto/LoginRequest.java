package com.wisread.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求 DTO（LoginRequest）。
 * 用于用户登录时提交凭证。
 */
public record LoginRequest(
        // 用户邮箱；不能为空且必须符合邮箱格式
        @NotBlank @Email String email,
        // 用户密码；不能为空
        @NotBlank String password
) {
}
