package com.wisread.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {

    private static final int MAX_BATCH_SIZE = 20;

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public List<float[]> embed(List<String> texts) {
        List<float[]> embeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            embeddings.addAll(embeddingModel.embed(texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()))));
        }
        return embeddings;
    }
}
