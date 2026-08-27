package com.wisread;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * “智阅”Spring Boot 应用入口。
 * 作为整个文档问答（RAG）后端服务的启动类，负责组件扫描、配置属性绑定与异步支持。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class WisreadApplication {

    /**
     * 程序主入口，启动内嵌 Web 容器并加载全部 Spring Bean。
     */
    public static void main(String[] args) {
        SpringApplication.run(WisreadApplication.class, args);
    }
}
