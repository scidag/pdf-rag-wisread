package com.wisread.service.impl;

import com.wisread.model.ChunkSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 补检候选合并算法单测（spec 4.2.3 合并去重 + 首轮保底配额 + §8.B 清单）。
 * 不变式：合并池包含首轮全部片段（AC8a）；最终注入首轮保底 ceil(K/2) 席（AC8b）；
 * 排序按（距离, chunkId）升序，确定性可复现。
 */
class CandidateMergeTest {

    // 构造一条命中：chunkId / distance / content 可区分，其余字段随意
    private ChunkSearchResult hit(long chunkId, double distance, String content) {
        return new ChunkSearchResult(chunkId, 100L, "test.pdf", content, 1, 1, distance);
    }

    @Test
    void quotaKeepsFirstRoundMajority() {
        // spec 4.2.3 示例表：首轮 A(0.10) B(0.15) C(0.20)，补检 D(0.08) E(0.12)，K=3
        // G=min(3, ceil(3/2))=2 → 首轮保底 A、B；补检新增取 1 席 → D；C 被挤出
        List<ChunkSearchResult> firstRound = List.of(
                hit(1, 0.10, "A"), hit(2, 0.15, "B"), hit(3, 0.20, "C"));
        List<ChunkSearchResult> retryRound = List.of(
                hit(4, 0.08, "D"), hit(5, 0.12, "E"));

        List<ChunkSearchResult> merged = KnowledgeRagOrchestratorImpl.mergeWithFirstRoundQuota(
                firstRound, retryRound, 3);

        assertThat(merged).extracting(ChunkSearchResult::content)
                .containsExactly("D", "A", "B");
        assertThat(merged).extracting(ChunkSearchResult::distance)
                .containsExactly(0.08, 0.10, 0.15);
    }

    @Test
    void dedupKeepsSmallerDistance() {
        // 同 chunkId：首轮 0.30、补检 0.10 → 合并池保留距离更小者
        List<ChunkSearchResult> firstRound = List.of(hit(1, 0.30, "first"));
        List<ChunkSearchResult> retryRound = List.of(hit(1, 0.10, "retry"));

        List<ChunkSearchResult> pool = KnowledgeRagOrchestratorImpl.dedup(firstRound, retryRound);

        assertThat(pool).hasSize(1);
        assertThat(pool.get(0).distance()).isEqualTo(0.10);
    }

    @Test
    void dedupEqualDistanceKeepsFirstRound() {
        // 同 chunkId 同距离 → 保留首轮实例（用 content 区分实例身份）
        List<ChunkSearchResult> firstRound = List.of(hit(1, 0.30, "first"));
        List<ChunkSearchResult> retryRound = List.of(hit(1, 0.30, "retry"));

        List<ChunkSearchResult> pool = KnowledgeRagOrchestratorImpl.dedup(firstRound, retryRound);

        assertThat(pool).hasSize(1);
        assertThat(pool.get(0).content()).isEqualTo("first");
    }

    @Test
    void poolKeepsAllFirstRoundWhenCapLarge() {
        // K=10 充裕：首轮 3 + 补检新增 2 全部保留（AC8a 在 K 充裕时的体现），按距离升序
        List<ChunkSearchResult> firstRound = List.of(
                hit(1, 0.10, "A"), hit(2, 0.15, "B"), hit(3, 0.20, "C"));
        List<ChunkSearchResult> retryRound = List.of(
                hit(4, 0.08, "D"), hit(5, 0.12, "E"));

        List<ChunkSearchResult> merged = KnowledgeRagOrchestratorImpl.mergeWithFirstRoundQuota(
                firstRound, retryRound, 10);

        assertThat(merged).hasSize(5);
        assertThat(merged).extracting(ChunkSearchResult::content)
                .containsExactly("D", "A", "E", "B", "C");
    }

    @Test
    void equalDistanceSortedByChunkId() {
        // 两个等距离片段 → chunkId 升序（确定性排序，不依赖 Map 遍历顺序）
        // 0.10 组：chunkId 5(D) 与 9(A) → 5 在前；0.15 组：chunkId 2(B) 与 105(C) → 2 在前
        List<ChunkSearchResult> firstRound = List.of(
                hit(9, 0.10, "A"), hit(2, 0.15, "B"));
        List<ChunkSearchResult> retryRound = List.of(
                hit(5, 0.10, "D"), hit(105, 0.15, "C"));

        List<ChunkSearchResult> merged = KnowledgeRagOrchestratorImpl.mergeWithFirstRoundQuota(
                firstRound, retryRound, 10);

        assertThat(merged).extracting(ChunkSearchResult::content)
                .containsExactly("D", "A", "B", "C");
    }

    @Test
    void retryOnlyDuplicatesYieldFirstRound() {
        // 补检全为首轮已有 chunkId（距离更大）→ 无新增席位可用，结果与首轮一致（AC8a 不丢首轮）
        List<ChunkSearchResult> firstRound = List.of(
                hit(1, 0.10, "A"), hit(2, 0.15, "B"));
        List<ChunkSearchResult> retryRound = List.of(
                hit(1, 0.40, "A2"), hit(2, 0.50, "B2"));

        List<ChunkSearchResult> merged = KnowledgeRagOrchestratorImpl.mergeWithFirstRoundQuota(
                firstRound, retryRound, 3);

        assertThat(merged).extracting(ChunkSearchResult::content)
                .containsExactly("A", "B");
    }

    @Test
    void emptyFirstRoundTakesRetryOnly() {
        // 首轮空、补检 5 条、K=3 → 距离最优 3 条
        List<ChunkSearchResult> retryRound = List.of(
                hit(1, 0.50, "A"), hit(2, 0.10, "B"), hit(3, 0.30, "C"),
                hit(4, 0.20, "D"), hit(5, 0.40, "E"));

        List<ChunkSearchResult> merged = KnowledgeRagOrchestratorImpl.mergeWithFirstRoundQuota(
                List.of(), retryRound, 3);

        assertThat(merged).extracting(ChunkSearchResult::content)
                .containsExactly("B", "D", "C");
    }

    @Test
    void emptyRetryKeepsFirstRound() {
        // 补检空 → 结果 = 首轮原样（首轮 ≤ 保底席位 G）
        List<ChunkSearchResult> firstRound = List.of(
                hit(1, 0.10, "A"), hit(2, 0.15, "B"));

        List<ChunkSearchResult> merged = KnowledgeRagOrchestratorImpl.mergeWithFirstRoundQuota(
                firstRound, List.of(), 10);

        assertThat(merged).extracting(ChunkSearchResult::content)
                .containsExactly("A", "B");
    }

    @Test
    void kEqualsOneGivesAllSeatsToFirstRound() {
        // K=1：G=ceil(1/2)=1 全给首轮，补检 0 席（即使补检更优）
        List<ChunkSearchResult> firstRound = List.of(hit(1, 0.10, "A"));
        List<ChunkSearchResult> retryRound = List.of(hit(2, 0.05, "B"));

        List<ChunkSearchResult> merged = KnowledgeRagOrchestratorImpl.mergeWithFirstRoundQuota(
                firstRound, retryRound, 1);

        assertThat(merged).extracting(ChunkSearchResult::content)
                .containsExactly("A");
    }
}
