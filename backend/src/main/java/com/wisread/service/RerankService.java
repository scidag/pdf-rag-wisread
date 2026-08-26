package com.wisread.service;

import com.wisread.model.ChunkSearchResult;

import java.util.List;

public interface RerankService {

    List<ChunkSearchResult> rerank(String query, List<ChunkSearchResult> candidates);
}
