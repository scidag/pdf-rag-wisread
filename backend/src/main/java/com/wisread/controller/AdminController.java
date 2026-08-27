package com.wisread.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 管理员（Admin）控制器。
 * 负责面向系统管理员的 REST 端点，当前提供运行健康检查 / 连通性探测示例接口。
 * 该类下的所有接口仅允许 ADMIN 角色访问（由 SecurityConfig 中的 hasRole("ADMIN") 控制）。
 * 基础路径：/api/v1/admin
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    /** GET /api/v1/admin/ping：管理员连通性探测接口（仅 ADMIN 角色可访问，由 SecurityConfig 的 hasRole("ADMIN") 控制）。
     * 入参：无。
     * 业务含义：返回服务是否正常以及当前服务器时间，用于健康检查 / 探活。
     * 返回：包含 status=ok 与 time（当前时间戳）的 JSON 对象。 */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "time", Instant.now().toString()
        ));
    }
}
