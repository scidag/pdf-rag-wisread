package com.wisread.service.impl;

import com.wisread.service.MinioStorageService;

import com.wisread.config.WisreadMinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * 对象存储服务的实现（基于 MinIO，S3 兼容）。
 * 实现要点：所有操作都绑定到配置的存储桶（bucket）上；fileKey 是对象的唯一标识键，
 * 一般带路径前缀（如“用户ID/文档ID/文件名”），用于分类存放与防止不同用户文件重名冲突。
 * 任意底层异常都被包装为 {@link IllegalStateException} 向上抛出，便于上层统一处理。
 */
@Service
public class MinioStorageServiceImpl implements MinioStorageService {

    private final MinioClient minioClient;
    private final WisreadMinioProperties properties;

    public MinioStorageServiceImpl(MinioClient minioClient, WisreadMinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    // 容器启动后确保目标存储桶存在，避免首次上传因桶不存在而失败
    @PostConstruct
    void init() {
        ensureBucket();
    }

    /**
     * 若存储桶不存在则创建之。
     * 为什么在初始化时检查：上传前保证桶就绪，避免每次上传都去探测，也防止运行期突然建桶失败。
     */
    public void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(properties.getBucket()).build()
            );
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(properties.getBucket()).build()
                );
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to initialize MinIO bucket", exception);
        }
    }

    /**
     * 上传对象。fileKey（即参数 key）决定对象在桶内的唯一位置，contentType 记录 MIME 类型以便下载时识别。
     */
    public void putObject(String key, byte[] content, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(key)
                            // 以字节流方式上传；-1 表示由 SDK 按流长度自动判断（对象大小已知）
                            .stream(new ByteArrayInputStream(content), content.length, -1)
                            .contentType(contentType)
                            .build()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("failed to upload object to MinIO", exception);
        }
    }

    /**
     * 按 fileKey 删除对象。用于文档删除/替换时清理原始文件。
     */
    public void deleteObject(String key) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(key)
                            .build()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("failed to delete object from MinIO", exception);
        }
    }

    /**
     * 按 fileKey 下载对象并完整读入字节数组。用于解析阶段把原始 PDF 取回本地处理。
     */
    public byte[] getObject(String key) {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(properties.getBucket())
                        .object(key)
                        .build()
        )) {
            return stream.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to read object from MinIO", exception);
        }
    }
}
