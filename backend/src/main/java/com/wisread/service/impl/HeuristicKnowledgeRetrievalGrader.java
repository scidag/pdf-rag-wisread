package com.wisread.service.impl;

import com.wisread.entity.KnowledgeBase;
import com.wisread.model.ChunkSearchResult;
import com.wisread.model.GradeResult;
import com.wisread.service.KnowledgeRetrievalGrader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索充分性启发式评估器（v0，纯启发式，不调 LLM、零额外延迟）。
 *
 * <p>固定判定顺序（spec 4.2.4，串行 guard 消除规则排列歧义）：
 * <ol>
 *   <li>候选为空 → NO_HITS（不充分，尝试补检）；</li>
 *   <li>库内已索引 chunk 总数 ≤ L → SMALL_CORPUS（跳过补检；豁免 ≠ 充分）；</li>
 *   <li>候选数 &lt; L → LOW_HITS（不充分，尝试补检）；</li>
 *   <li>top1 相似度 &lt; 弱命中线 → WEAK_TOP_HIT（不充分，尝试补检）；</li>
 *   <li>其余 → SUFFICIENT。</li>
 * </ol>
 *
 * <p>弱命中判定说明：候选已过阈值过滤（distance ≤ 1 - threshold），弱命中只能用
 * "阈值 + 余量（margin）"判定比阈值好不了多少的边际命中；未配置阈值（管线允许 null，
 * 此时不过滤）时退回 0.5 绝对兜底线。相似度 = 1 - 余弦距离，与管线口径一致，禁止第二种换算。
 *
 * <p>小库豁免优先于弱命中：库内语料已全部参与检索判定时，换检索角度也不会带来新候选。
 * L = min(单轮口径上限 K, lowHitsMin)，K 由 {@link #resolveSingleRoundCap} 定义。
 */
@Service
public class HeuristicKnowledgeRetrievalGrader implements KnowledgeRetrievalGrader {

    private static final Logger log = LoggerFactory.getLogger(HeuristicKnowledgeRetrievalGrader.class);

    private final int lowHitsMin;       // LOW_HITS 下限 L 的上限之一（默认 3）
    private final double weakTopMargin; // 弱命中余量（默认 0.05）
    private final double weakTopAbsolute; // 未配置阈值时的绝对兜底线（默认 0.5）
    private final int fallbackTopK;     // 知识库未配置 topK 时的回退默认值

    public HeuristicKnowledgeRetrievalGrader(
            @Value("${wisread.knowledge.agent.grader.low-hits-min:3}") int lowHitsMin,
            @Value("${wisread.knowledge.agent.grader.weak-top-margin:0.05}") double weakTopMargin,
            @Value("${wisread.knowledge.agent.grader.weak-top-absolute:0.5}") double weakTopAbsolute,
            @Value("${wisread.knowledge.agent.fallback-top-k:10}") int fallbackTopK
    ) {
        this.lowHitsMin = lowHitsMin;
        this.weakTopMargin = weakTopMargin;
        this.weakTopAbsolute = weakTopAbsolute;
        this.fallbackTopK = fallbackTopK;
    }

    @Override
    public GradeResult grade(List<ChunkSearchResult> candidates, KnowledgeBase knowledgeBase, long indexedChunkCount) {
        // 固定判定顺序：空 → 小库豁免 → 命中不足 → 弱命中 → 充分
        if (candidates == null || candidates.isEmpty()) {
            return new GradeResult(false, GradeResult.Reason.NO_HITS);
        }
        int singleRoundCap = KnowledgeRetrievalGrader.resolveSingleRoundCap(knowledgeBase, fallbackTopK);
        int lowHitsBar = Math.min(singleRoundCap, lowHitsMin);
        if (indexedChunkCount <= lowHitsBar) {
            // 小库豁免：跳过补检（语义是"补检无新增收益"，不是"结果充分"）
            return new GradeResult(false, GradeResult.Reason.SMALL_CORPUS);
        }
        if (candidates.size() < lowHitsBar) {
            return new GradeResult(false, GradeResult.Reason.LOW_HITS);
        }
        double top1Similarity = 1 - candidates.get(0).distance();
        double weakLine = knowledgeBase.getSimilarityThreshold() != null
                ? Math.min(knowledgeBase.getSimilarityThreshold() + weakTopMargin, 1.0)
                : weakTopAbsolute;
        if (top1Similarity < weakLine) {
            return new GradeResult(false, GradeResult.Reason.WEAK_TOP_HIT);
        }
        if (log.isDebugEnabled()) {
            log.debug("检索充分性判定 knowledgeBaseId={} hits={} top1Similarity={} weakLine={} reason=SUFFICIENT",
                    knowledgeBase.getId(), candidates.size(), top1Similarity, weakLine);
        }
        return new GradeResult(true, GradeResult.Reason.SUFFICIENT);
    }
}
