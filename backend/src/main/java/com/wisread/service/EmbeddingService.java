package com.wisread.service;

import java.util.List;

/**
 * 文本向量化（Embedding）服务接口。
 * 职责：将一段段文本转换为高维稠密向量，供 pgvector 存储与余弦相似度检索使用。
 * 实现上需考虑批量调用以分摊请求开销，并记录 token 用量用于计费。
 */
public interface EmbeddingService {

    /**
     * 将文本列表批量转换为向量列表。
     *
     * <p>做什么：对输入文本调用底层 Embedding 模型，返回与输入顺序一致的 float[] 向量集合。</p>
     *
     * <p>为什么：RAG 检索依赖向量相似度，所有待入库 chunk 与用户查询都必须先向量化；
     * 同时需按 userId 记录 token 消耗，支撑用量计费。具体批大小与模型名由实现决定。</p>
     *
     * @param texts  待向量化的文本列表（通常为文档 chunk 或用户问题）
     * @param userId 调用用户 ID，用于用量日志归属
     * @return 与 texts 一一对应的向量列表
     */
    List<float[]> embed(List<String> texts, Long userId);
}
