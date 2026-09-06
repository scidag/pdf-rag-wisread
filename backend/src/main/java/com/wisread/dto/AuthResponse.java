package com.wisread.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 认证响应 DTO（AuthResponse）。
 * 用于注册 / 登录 / 刷新令牌等认证接口返回给客户端的认证结果。
 */
public record AuthResponse(
        // 访问令牌（access token），客户端后续请求需通过 Authorization: Bearer 携带
        String accessToken,
        // 刷新令牌（refresh token）。FR-3：不序列化到响应体——一律且仅通过
        // HttpOnly Set-Cookie 下发；控制器内部仍需读取该值写 Cookie。
        @JsonIgnore String refreshToken,
        // access token 的有效期（单位：秒）
        long expiresIn,
        // 当前登录用户的基本信息
        UserResponse user
) {
}
