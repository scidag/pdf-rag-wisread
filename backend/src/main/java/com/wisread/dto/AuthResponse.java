package com.wisread.dto;

/**
 * 认证响应 DTO（AuthResponse）。
 * 用于注册 / 登录 / 刷新令牌等认证接口返回给客户端的认证结果。
 */
public record AuthResponse(
        // 访问令牌（access token），客户端后续请求需通过 Authorization: Bearer 携带
        String accessToken,
        // 刷新令牌（refresh token），用于换取新的 access token，通常以 HttpOnly Cookie 下发
        String refreshToken,
        // access token 的有效期（单位：秒）
        long expiresIn,
        // 当前登录用户的基本信息
        UserResponse user
) {
}
