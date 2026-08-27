package com.wisread.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储客户端配置。
 * “智阅”系统使用 MinIO 存储上传的文档、图片等文件。本类基于
 * {@link WisreadMinioProperties} 中的连接信息创建并注册一个 {@link MinioClient} Bean，
 * 供文件上传/下载服务注入使用。
 */
@Configuration
public class MinioConfig {

    /**
     * 构建 MinIO 客户端单例。
     * 根据配置中的服务地址、访问密钥与私有密钥初始化连接，
     * 该 Bean 被其他组件（如文件服务）注入以操作对象存储。
     */
    @Bean
    MinioClient minioClient(WisreadMinioProperties properties) {
        return MinioClient.builder()
                // MinIO 服务地址，例如 http://localhost:9000
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
