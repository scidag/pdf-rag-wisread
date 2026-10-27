package com.wisread.service;

import java.io.IOException;
import java.io.InputStream;

/**
 * 对象存储服务接口（基于 MinIO/S3 兼容协议）。
 * 职责：为系统提供统一的二进制对象读写能力，主要用于保存用户上传的原始文件
 * （如 PDF），使其与业务数据库解耦，并支持后续按需下载解析或彻底删除。
 */
public interface MinioStorageService {

    /**
     * 打开的对象流及其大小（字节）。
     * size 取自 MinIO 响应头 Content-Length，未知时为 -1；
     * 流式读取避免大文件整体载入堆内存，调用方（或响应写完后由框架）负责关闭流。
     */
    record StoredObject(InputStream stream, long size) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
            }
        }
    }

    /**
     * 上传一个对象到存储桶。
     *
     * @param key         对象的唯一键（fileKey），通常包含路径前缀以便分类与防重名
     * @param content     对象二进制内容
     * @param contentType 对象的 MIME 类型，如 application/pdf
     */
    void putObject(String key, byte[] content, String contentType);

    /**
     * 按 key 删除一个对象。
     *
     * @param key 要删除对象的键（fileKey）
     */
    void deleteObject(String key);

    /**
     * 按 key 下载并完整读取一个对象的字节内容。
     *
     * @param key 要读取对象的键（fileKey）
     * @return 对象的全部字节
     */
    byte[] getObject(String key);

    /**
     * 按 key 打开对象的输入流（流式读取，不整体载入内存）。
     * 供大文件预览/下载端点直接向浏览器透传，避免 byte[] 全量占用堆内存。
     *
     * @param key 要读取对象的键（fileKey）
     * @return 对象流与大小；响应写完后由 Spring 框架负责关闭流
     */
    StoredObject openObject(String key);
}
