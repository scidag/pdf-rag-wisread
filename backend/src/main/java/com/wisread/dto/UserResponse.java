package com.wisread.dto;

/**
 * 用户响应 DTO（UserResponse）。
 * 用于返回用户的基本资料信息。
 */
public record UserResponse(
        // 用户 ID
        Long id,
        // 用户名
        String username,
        // 用户邮箱
        String email
) {
}
