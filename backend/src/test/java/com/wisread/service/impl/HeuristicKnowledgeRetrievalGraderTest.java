package com.wisread.service.impl;

import com.wisread.entity.KnowledgeBase;
import com.wisread.model.ChunkSearchResult;
import com.wisread.model.GradeResult;
import com.wisread.service.KnowledgeRetrievalGrader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索充分性启发式评估器单测（spec 4.2.4 固定判定顺序 + §8.A 清单）。
 * 判定顺序：空候选 → 小库豁免 → 命中不足 → 弱命中 → 充分。
 * 相似度口径：similarity = 1 - distance（余弦距离唯一换算，禁止第二种推导）。
 */
class HeuristicKnowledgeRetrievalGraderTest {

    private final KnowledgeRetrievalGrader grader =
            new HeuristicKnowledgeRetrievalGrader(3, 0.05, 0.5, 10);

    // 构造一条命中（距离可变，其余字段随意）
    private ChunkSearchResult hit(long chunkId, double distance) {
        return new ChunkSearchResult(chunkId, 100L, "test.pdf", "内容", 1, 1, distance);
    }

    // 构造知识库：rerankK 与相似度阈值可变（与线上默认值对齐）
    private KnowledgeBase kb(Integer rerankK, Double threshold) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setTopK(10);
        knowledgeBase.setRerankEnabled(true);
        knowledgeBase.setRerankK(rerankK);
        knowledgeBase.setSimilarityThreshold(threshold);
        return knowledgeBase;
    }

    @Test
    void emptyCandidatesReturnsNoHits() {
        GradeResult result = grader.grade(List.of(), kb(3, 0.65), 100);

        assertThat(result.reason()).isEqualTo(GradeResult.Reason.NO_HITS);
        assertThat(result.sufficient()).isFalse();
    }

    @Test
    void smallCorpusTakesPriorityOverWeakTopHit() {
        // count=2 ≤ L=3（小库），top1 similarity=0.41 < 0.45（弱命中）——两条规则同时满足时小库豁免优先
        GradeResult result = grader.grade(
                List.of(hit(1, 0.59), hit(2, 0.70)), kb(3, 0.40), 2);

        assertThat(result.reason()).isEqualTo(GradeResult.Reason.SMALL_CORPUS);
    }

    @Test
    void smallCorpusIsNotSufficient() {
        // 豁免 ≠ 充分：SMALL_CORPUS 仅表示补检无新增收益，不得混入充分统计
        GradeResult result = grader.grade(
                List.of(hit(1, 0.59), hit(2, 0.70)), kb(3, 0.40), 2);

        assertThat(result.sufficient()).isFalse();
    }

    @Test
    void lowHitsBoundaryIsStrict() {
        // 候选数 = L=3（rerankK=3 → L=min(3,3)=3）时严格不小于，不触发 LOW_HITS
        GradeResult result = grader.grade(
                List.of(hit(1, 0.10), hit(2, 0.20), hit(3, 0.30)), kb(3, 0.65), 100);

        assertThat(result.reason()).isEqualTo(GradeResult.Reason.SUFFICIENT);
    }

    @Test
    void lowHitsWhenCandidatesBelowBar() {
        GradeResult result = grader.grade(
                List.of(hit(1, 0.10), hit(2, 0.20)), kb(3, 0.65), 100);

        assertThat(result.reason()).isEqualTo(GradeResult.Reason.LOW_HITS);
        assertThat(result.sufficient()).isFalse();
    }

    @Test
    void weakTopHitBoundaryIsStrict() {
        // top1 similarity == 弱命中线时严格不小于，不触发 WEAK_TOP_HIT。
        // 边界值用二进制浮点可精确表示的数（线 0.5 = 0.4 + 0.1，sim 0.5 = 1 - 0.5），
        // 避免类似 1 - 0.55 = 0.4499...9 的浮点误差把"恰好在线上"误判为弱命中
        KnowledgeRetrievalGrader exactGrader = new HeuristicKnowledgeRetrievalGrader(3, 0.1, 0.5, 10);
        GradeResult result = exactGrader.grade(
                List.of(hit(1, 0.5), hit(2, 0.60), hit(3, 0.70)), kb(3, 0.4), 100);

        assertThat(result.reason()).isEqualTo(GradeResult.Reason.SUFFICIENT);
    }

    @Test
    void weakTopHitWhenJustAboveThreshold() {
        // top1 similarity=0.42 < 线 0.45 → 弱命中
        GradeResult result = grader.grade(
                List.of(hit(1, 0.58), hit(2, 0.60), hit(3, 0.70)), kb(3, 0.40), 100);

        assertThat(result.reason()).isEqualTo(GradeResult.Reason.WEAK_TOP_HIT);
        assertThat(result.sufficient()).isFalse();
    }

    @Test
    void nullThresholdFallsBackToAbsolute() {
        // 未配置阈值时走 0.5 绝对兜底线：sim=0.49 < 0.5 → 弱命中
        GradeResult result = grader.grade(
                List.of(hit(1, 0.51), hit(2, 0.60), hit(3, 0.70)), kb(3, null), 100);

        assertThat(result.reason()).isEqualTo(GradeResult.Reason.WEAK_TOP_HIT);
    }

    @Test
    void marginCappedAtOne() {
        // threshold=0.98 + margin=0.05 = 1.03 封顶到 1.0：sim=0.99 < 1.0 → 弱命中（不越界）
        GradeResult result = grader.grade(
                List.of(hit(1, 0.01), hit(2, 0.02), hit(3, 0.03)), kb(3, 0.98), 100);

        assertThat(result.reason()).isEqualTo(GradeResult.Reason.WEAK_TOP_HIT);
    }

    @Test
    void rerankDisabledCapFallsBackToTopK() {
        KnowledgeBase rerankOff = new KnowledgeBase();
        rerankOff.setRerankEnabled(false);
        rerankOff.setTopK(10);

        KnowledgeBase rerankOnWithK = new KnowledgeBase();
        rerankOnWithK.setRerankEnabled(true);
        rerankOnWithK.setRerankK(5);

        KnowledgeBase rerankOnNoK = new KnowledgeBase();
        rerankOnNoK.setRerankEnabled(true);
        rerankOnNoK.setRerankK(null);
        rerankOnNoK.setTopK(null);

        assertThat(KnowledgeRetrievalGrader.resolveSingleRoundCap(rerankOff, 10)).isEqualTo(10);
        assertThat(KnowledgeRetrievalGrader.resolveSingleRoundCap(rerankOnWithK, 10)).isEqualTo(5);
        assertThat(KnowledgeRetrievalGrader.resolveSingleRoundCap(rerankOnNoK, 10)).isEqualTo(10);
    }
}
