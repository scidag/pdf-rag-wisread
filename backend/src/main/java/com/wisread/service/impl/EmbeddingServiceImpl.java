package com.wisread.service.impl;

import com.wisread.service.EmbeddingService;
import com.wisread.service.TokenCounter;
import com.wisread.service.UsageLogService;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingServiceImpl implements EmbeddingService {

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

    public List<float[]> embed(List<String> texts, Long userId) {
        List<float[]> embeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            embeddings.addAll(embeddingModel.embed(batch));
            int inputTokens = batch.stream().mapToInt(tokenCounter::count).sum();
            usageLogService.log(userId, embeddingModelName, inputTokens, 0);
        }
        return embeddings;
    }
}
