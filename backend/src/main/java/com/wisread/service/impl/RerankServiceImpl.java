package com.wisread.service.impl;

import com.wisread.service.RerankService;

import com.wisread.model.ChunkSearchResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RerankServiceImpl implements RerankService {

    public List<ChunkSearchResult> rerank(String query, List<ChunkSearchResult> candidates) {
        return candidates.stream().limit(3).toList();
    }
}
