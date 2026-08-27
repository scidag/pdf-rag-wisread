package com.wisread.controller;

import com.wisread.dto.UserResponse;
import com.wisread.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户（User）控制器。
 * 负责当前登录用户相关的 REST 端点，目前提供“获取当前用户信息”接口。
 * 所有接口均需登录（从 Authentication 获取当前用户 ID）。
 * 基础路径：/api/v1/users
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    /** GET /api/v1/users/me：获取当前登录用户的信息。
     * 入参：无查询参数，当前登录用户由 Authentication 解析得到。
     * 业务含义：返回当前登录用户的基本资料。
     * 返回：200 OK 及用户信息（UserResponse：ID/用户名/邮箱）。 */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(authService.getCurrentUser(userId));
    }
}
