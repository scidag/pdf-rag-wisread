package com.wisread.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wisread.minio")
public class WisreadMinioProperties {

    /**
     * MinIO 服务地址。
     * 来源：{@code wisread.minio.endpoint}，如 http://localhost:9000。
     */
    private String endpoint;

    /**
     * MinIO 访问密钥（Access Key / 账号）。
     * 来源：{@code wisread.minio.access-key}。
     */
    private String accessKey;

    /**
     * MinIO 私有密钥（Secret Key / 密码）。
     * 来源：{@code wisread.minio.secret-key}，属敏感凭据，建议通过环境变量注入。
     */
    private String secretKey;

    /**
     * 默认存储桶（Bucket）名称。
     * 来源：{@code wisread.minio.bucket}，上传文件存放的桶。
     */
    private String bucket;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
}
