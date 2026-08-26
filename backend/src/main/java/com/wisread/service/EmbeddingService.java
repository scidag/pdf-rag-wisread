package com.wisread.service;

import java.util.List;

public interface EmbeddingService {

    List<float[]> embed(List<String> texts, Long userId);
}
