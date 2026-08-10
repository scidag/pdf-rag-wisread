package com.wisread.service;

import com.wisread.model.ChunkSearchResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RerankService {

    public List<ChunkSearchResult> rerank(String query, List<ChunkSearchResult> candidates) {
        return candidates.stream().limit(3).toList();
    }
}
