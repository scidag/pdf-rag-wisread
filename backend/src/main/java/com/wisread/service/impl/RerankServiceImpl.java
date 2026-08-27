package com.wisread.service.impl;

import com.wisread.service.RerankService;

import com.wisread.model.ChunkSearchResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 重排服务实现（RerankServiceImpl）。
 *
 * <p>占位实现（TODO）：当前尚未接入真正的重排模型（如 cross-encoder），仅做简单截断，
 * 取向量召回候选的前 3 个作为最终结果。后续应替换为按语义相关性重排序的实现，
 * 以在保持召回广度的同时提升精排质量。
 */
@Service
public class RerankServiceImpl implements RerankService {

    /**
     * 重排候选块。
     *
     * <p>当前实现为占位逻辑：仅保留前 3 个候选（依赖向量召回的近似排序），
     * 未做真实重排。注意：这意味着真正参与回答的上下文最多 3 块（[1]..[3]）。
     */
    public List<ChunkSearchResult> rerank(String query, List<ChunkSearchResult> candidates) {
        // 占位：仅截前 3，待接入真实重排模型
        return candidates.stream().limit(3).toList();
    }
}
