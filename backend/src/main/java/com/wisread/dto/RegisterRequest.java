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
        @NotBlank(message = "用户名不能为空")
        @Size(max = 50, message = "用户名不能超过 50 个字符")
        String username,
        // 用户邮箱；不能为空，需符合邮箱格式，最多 100 个字符
        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱不能超过 100 个字符")
        String email,
        // 登录密码；不能为空，长度 8~64 个字符
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需为 8~64 位，可包含字母、数字和符号")
        String password
) {
}
