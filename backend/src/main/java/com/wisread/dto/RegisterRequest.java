package com.wisread.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求 DTO（RegisterRequest）。
 * 用于新用户注册时提交账号信息。
 */
public record RegisterRequest(
        // 用户名；不能为空，最多 50 个字符
        @NotBlank @Size(max = 50) String username,
        // 用户邮箱；不能为空，需符合邮箱格式，最多 100 个字符
        @NotBlank @Email @Size(max = 100) String email,
        // 登录密码；不能为空，长度 8~64 个字符
        @NotBlank @Size(min = 8, max = 64) String password
) {
}
