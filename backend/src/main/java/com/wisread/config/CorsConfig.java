package com.wisread.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域（CORS）配置。
 * 由于“智阅”前端与后端通常部署在不同源（域名/端口），需要显式放行
 * 指定的前端来源，否则浏览器会拦截跨域请求。本类基于 {@link WisreadCorsProperties}
 * 中配置的来源，对 {@code /api/**} 下的接口开启跨域支持。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final WisreadCorsProperties corsProperties;

    public CorsConfig(WisreadCorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    /**
     * 注册跨域映射规则。
     * 对所有 {@code /api/**} 接口放行配置的来源、任意方法与请求头，
     * 并允许携带凭证（Cookie/Authorization），因此来源不能为通配符 {@code *}。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.getAllowedOrigins().toArray(String[]::new))
                .allowedMethods("*")
                .allowedHeaders("*")
                // 允许携带凭证，配合具体来源而非通配符以保证安全
                .allowCredentials(true);
    }
}
