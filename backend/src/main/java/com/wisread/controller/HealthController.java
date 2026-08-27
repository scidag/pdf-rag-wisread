package com.wisread.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查（Health）控制器。
 * 提供简单的服务存活探测接口，供网关 / 负载均衡 / 运维探活使用，无需登录。
 * 基础路径：/api/v1
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    /** GET /api/v1/health：服务健康检查接口。
     * 入参：无。
     * 业务含义：返回服务是否正常运行。
     * 返回：200 OK 及 {"status":"ok"}。 */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
