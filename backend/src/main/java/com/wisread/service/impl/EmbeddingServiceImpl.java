package com.wisread.service.impl;

import com.wisread.service.EmbeddingService;
import com.wisread.service.TokenCounter;
import com.wisread.service.UsageLogService;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本向量化服务的实现。
 * 实现要点：基于 Spring AI 的 {@link EmbeddingModel} 调用远端 Embedding 模型；
 * 以批大小 {@code MAX_BATCH_SIZE=20} 分批请求以降低网络往返开销；模型名来自配置文件
 * （默认 qwen3.7-text-embedding，可用 {@code spring.ai.openai.embedding.options.model} 覆盖）；
 * 每批完成后按 userId 记录输入 token 用量（output 为 0，Embedding 无生成输出）。
 */
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    // 单次请求最多携带的文本条数；避免单次请求体过大或超出模型批量上限
    private static final int MAX_BATCH_SIZE = 20;

    private final EmbeddingModel embeddingModel;
    private final UsageLogService usageLogService;
    private final TokenCounter tokenCounter;
    private final String embeddingModelName;

    public EmbeddingServiceImpl(
            EmbeddingModel embeddingModel,
            UsageLogService usageLogService,
            TokenCounter tokenCounter,
            @Value("${spring.ai.openai.embedding.options.model:qwen3.7-text-embedding}") String embeddingModelName
    ) {
        this.embeddingModel = embeddingModel;
        this.usageLogService = usageLogService;
        this.tokenCounter = tokenCounter;
        this.embeddingModelName = embeddingModelName;
    }

    /**
     * 批量向量化文本。
     * 为什么分批：Embedding 模型对单次批量大小有限制且批量请求更省时；
     * 为什么记录 token：为用量计费提供输入侧数据，userId 用于归属统计。
     */
    public List<float[]> embed(List<String> texts, Long userId) {
        List<float[]> embeddings = new ArrayList<>(texts.size());
        // 以 MAX_BATCH_SIZE 为步长切片，逐批调用模型
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            embeddings.addAll(embeddingModel.embed(batch));
            // 估算本批输入 token 并落库计费日志
            int inputTokens = batch.stream().mapToInt(tokenCounter::count).sum();
            usageLogService.log(userId, embeddingModelName, inputTokens, 0);
        }
        return embeddings;
    }
}
