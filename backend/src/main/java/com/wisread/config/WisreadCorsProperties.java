package com.wisread.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "wisread.cors")
public class WisreadCorsProperties {

    /** 允许跨域的前端来源，注意启用 credentials 时不能使用通配符 * */
    private List<String> allowedOrigins = List.of("http://localhost:3000", "http://127.0.0.1:3000");

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
