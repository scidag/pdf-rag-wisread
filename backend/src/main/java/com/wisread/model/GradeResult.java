package com.wisread.model;

/**
 * 检索充分性评估结果（Agentic RAG 补检索判定，内存模型不持久化）。
 * sufficient 表示启发式判定"检索结果充分"；reason 为判定原因，
 * 语义约定（spec 4.2.4）：SMALL_CORPUS 仅表示"库规模足够小，补检预期无新增收益"，
 * 不代表检索结果充分，也不代表已召回全部语料。
 */
public record GradeResult(
        // 是否判定充分（NO_HITS/SMALL_CORPUS/LOW_HITS/WEAK_TOP_HIT 均为不充分）
        boolean sufficient,
        // 判定原因
        Reason reason
) {

    /**
     * 判定原因枚举（固定判定顺序：NO_HITS → SMALL_CORPUS → LOW_HITS → WEAK_TOP_HIT → SUFFICIENT）。
     */
    public enum Reason {
        /** 候选为空（尝试补检） */
        NO_HITS,
        /** 小库豁免：库内 chunk 总数 ≤ L，跳过补检（不充分但换检索角度无收益） */
        SMALL_CORPUS,
        /** 命中数 < L（尝试补检） */
        LOW_HITS,
        /** top1 相似度低于弱命中线（尝试补检） */
        WEAK_TOP_HIT,
        /** 充分 */
        SUFFICIENT
    }
}
