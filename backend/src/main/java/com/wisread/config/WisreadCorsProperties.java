package com.wisread.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "wisread.cors")
public class WisreadCorsProperties {

    /**
     * 允许跨域访问的前端来源（Origin）列表。
     * 来源：配置文件 {@code wisread.cors.allowed-origins}（如 application.yml）。
     * 注意：由于跨域开启了 credentials，来源不能使用通配符 {@code *}，
     * 必须显式列出可信前端域名；默认开发环境为本地 3000 端口。
     */
    private List<String> allowedOrigins = List.of("http://localhost:3000", "http://127.0.0.1:3000");

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
