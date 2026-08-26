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

@Service
public class MinioStorageServiceImpl implements MinioStorageService {

    private final MinioClient minioClient;
    private final WisreadMinioProperties properties;

    public MinioStorageServiceImpl(MinioClient minioClient, WisreadMinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        ensureBucket();
    }

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

    public void putObject(String key, byte[] content, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(key)
                            .stream(new ByteArrayInputStream(content), content.length, -1)
                            .contentType(contentType)
                            .build()
            );
        } catch (Exception exception) {
            throw new IllegalStateException("failed to upload object to MinIO", exception);
        }
    }

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
