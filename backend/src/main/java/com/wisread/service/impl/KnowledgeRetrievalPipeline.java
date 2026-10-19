package com.wisread.service.impl;

import com.wisread.entity.KnowledgeBase;
import com.wisread.model.ChunkSearchResult;
import com.wisread.model.KnowledgeChunkSearchResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库检索口径的唯一定义点（Chat 绑库问答与 RAG 检索测试端点共用）。
 *
 * <p>为什么抽成静态工具：绑库问答（ChatServiceImpl）与检索测试（KnowledgeRetrievalTestServiceImpl）
 * 必须使用完全一致的"topK 解析 → 阈值过滤 → rerankK 截断"口径，
 * 集中在此处可保证两处行为永不漂移；改动口径只改这一个文件。
 *
 * <p>口径约定（与 ChatServiceImpl 绑库分支一致）：
 * <ul>
 *   <li>topK：优先取知识库配置，未配置时回退到调用方默认值；</li>
 *   <li>阈值过滤：余弦距离 = 1 - 余弦相似度，distance &gt; 1 - similarityThreshold 丢弃，
 *       阈值未配置（null）时不过滤；</li>
 *   <li>rerank 截断：rerankEnabled 为 true 且 rerankK 小于命中数时截前 rerankK 条，
 *       当前 rerank 为占位实现，不重排仅截断。</li>
 * </ul>
 */
public final class KnowledgeRetrievalPipeline {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalPipeline.class);

    private KnowledgeRetrievalPipeline() {
    }

    /**
     * 解析本次检索实际使用的 topK：知识库已配置用配置值，否则回退默认值。
     */
    public static int resolveTopK(KnowledgeBase knowledgeBase, int fallbackTopK) {
        Integer topK = knowledgeBase.getTopK();
        return topK != null ? topK : fallbackTopK;
    }

    /**
     * 对向量检索命中做阈值过滤与 rerankK 截断（顺序保持距离升序不变）。
     * 该方法是 Chat 问答与检索测试共用的唯一过滤口径。
     */
    public static List<ChunkSearchResult> filterAndTruncate(
            List<KnowledgeChunkSearchResult> hits,
            KnowledgeBase knowledgeBase
    ) {
        Double threshold = knowledgeBase.getSimilarityThreshold();
        List<ChunkSearchResult> filtered = new ArrayList<>(hits.size());
        for (KnowledgeChunkSearchResult hit : hits) {
            if (threshold == null || hit.distance() <= 1 - threshold) {
                filtered.add(new ChunkSearchResult(
                        hit.chunkId(),
                        hit.documentId(),
                        hit.filename(),
                        hit.content(),
                        hit.pageStart(),
                        hit.pageEnd(),
                        hit.locationLabel(),
                        hit.distance()
                ));
            }
        }
        // 记录阈值过滤口径：过滤前后条数，便于观测阈值配置是否过严导致全部命中被丢弃
        log.info("检索阈值过滤 knowledgeBaseId={} threshold={} before={} after={}",
                knowledgeBase.getId(), threshold, hits.size(), filtered.size());
        if (Boolean.TRUE.equals(knowledgeBase.getRerankEnabled())) {
            Integer rerankK = knowledgeBase.getRerankK();
            if (rerankK != null && rerankK < filtered.size()) {
                log.info("rerank 截断 knowledgeBaseId={} rerankK={} before={} after={}",
                        knowledgeBase.getId(), rerankK, filtered.size(), rerankK);
                return filtered.subList(0, rerankK);
            }
        }
        return filtered;
    }
}
