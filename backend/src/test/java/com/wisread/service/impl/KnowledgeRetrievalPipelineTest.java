package com.wisread.service.impl;

import com.wisread.entity.KnowledgeBase;
import com.wisread.model.ChunkSearchResult;
import com.wisread.model.KnowledgeChunkSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库检索口径（KnowledgeRetrievalPipeline）单元测试：
 * 验证 Chat 绑库问答与 RAG 检索测试共用口径的 topK 解析、阈值过滤与 rerankK 截断行为。
 */
class KnowledgeRetrievalPipelineTest {

    private KnowledgeBase knowledgeBase(Integer topK, Double threshold, Boolean rerankEnabled, Integer rerankK) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setTopK(topK);
        knowledgeBase.setSimilarityThreshold(threshold);
        knowledgeBase.setRerankEnabled(rerankEnabled);
        knowledgeBase.setRerankK(rerankK);
        return knowledgeBase;
    }

    private KnowledgeChunkSearchResult hit(long chunkId, double distance) {
        return new KnowledgeChunkSearchResult(
                chunkId, 601L, "kb文档.pdf", "知识库片段" + chunkId, 2, 3, "页", distance);
    }

    @Test
    void resolveTopKPrefersConfiguredValueAndFallsBack() {
        assertThat(KnowledgeRetrievalPipeline.resolveTopK(knowledgeBase(5, 0.65, true, 3), 10)).isEqualTo(5);
        assertThat(KnowledgeRetrievalPipeline.resolveTopK(knowledgeBase(null, 0.65, true, 3), 10)).isEqualTo(10);
    }

    @Test
    void filterAndTruncateFiltersByThresholdKeepingOrder() {
        // similarityThreshold=0.8 → 距离阈值 0.2：0.1 保留，0.25 丢弃
        List<ChunkSearchResult> result = KnowledgeRetrievalPipeline.filterAndTruncate(
                List.of(hit(1L, 0.1), hit(2L, 0.25)),
                knowledgeBase(10, 0.8, false, null));

        assertThat(result).extracting(ChunkSearchResult::chunkId).containsExactly(1L);
        assertThat(result.get(0).filename()).isEqualTo("kb文档.pdf");
        assertThat(result.get(0).content()).isEqualTo("知识库片段1");
        assertThat(result.get(0).pageStart()).isEqualTo(2);
        assertThat(result.get(0).pageEnd()).isEqualTo(3);
        assertThat(result.get(0).locationLabel()).isEqualTo("页");
    }

    @Test
    void filterAndTruncateKeepsAllWhenThresholdMissing() {
        List<ChunkSearchResult> result = KnowledgeRetrievalPipeline.filterAndTruncate(
                List.of(hit(1L, 0.1), hit(2L, 0.9)),
                knowledgeBase(10, null, false, null));

        assertThat(result).extracting(ChunkSearchResult::chunkId).containsExactly(1L, 2L);
    }

    @Test
    void filterAndTruncateTruncatesToRerankKWhenEnabled() {
        List<ChunkSearchResult> result = KnowledgeRetrievalPipeline.filterAndTruncate(
                List.of(hit(1L, 0.1), hit(2L, 0.2), hit(3L, 0.3)),
                knowledgeBase(10, 0.5, true, 2));

        assertThat(result).extracting(ChunkSearchResult::chunkId).containsExactly(1L, 2L);
    }

    @Test
    void filterAndTruncateIgnoresRerankKWhenDisabledOrOverflow() {
        KnowledgeBase disabled = knowledgeBase(10, 0.5, false, 1);
        List<ChunkSearchResult> notTruncated = KnowledgeRetrievalPipeline.filterAndTruncate(
                List.of(hit(1L, 0.1), hit(2L, 0.2)), disabled);
        assertThat(notTruncated).extracting(ChunkSearchResult::chunkId).containsExactly(1L, 2L);

        KnowledgeBase overflow = knowledgeBase(10, 0.5, true, 5);
        List<ChunkSearchResult> rerankKOversized = KnowledgeRetrievalPipeline.filterAndTruncate(
                List.of(hit(1L, 0.1), hit(2L, 0.2)), overflow);
        assertThat(rerankKOversized).extracting(ChunkSearchResult::chunkId).containsExactly(1L, 2L);
    }
}
