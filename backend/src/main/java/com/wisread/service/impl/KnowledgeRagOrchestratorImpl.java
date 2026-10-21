package com.wisread.service.impl;

import com.wisread.model.ChunkSearchResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库 Agentic RAG 编排器（阶段 1，spec 4.2.1）：编排"检索 → 评估 →（改写 → 重新 embed → 补检）→ 合并"
 * 的有界流程，Chat 绑库问答与检索测试端点共用此唯一入口，防止口径分叉。
 *
 * <p>本期不变式：
 * <ul>
 *   <li>所有轮次召回均经 {@link KnowledgeRetrievalPipeline#filterAndTruncate}，编排器不复制第二套过滤/截断逻辑；</li>
 *   <li>合并池包含首轮全部片段（AC8a），最终注入首轮保底 ceil(K/2) 席（AC8b）；</li>
 *   <li>所有路径收敛为 {@link com.wisread.model.RetrievalOutcome}（spec 4.6 降级决策表）。</li>
 * </ul>
 *
 * <p>本类当前仅含合并纯函数（确定性排序，不依赖任何 Map 遍历顺序）；编排流程与依赖注入在补全任务中实现。
 */
@Service
public class KnowledgeRagOrchestratorImpl {

    /**
     * 合并去重（AC8a，spec 4.2.3）：以首轮列表为基底构成合并池——
     * 补检条目按 chunkId 查重，已存在时保留距离更小者（距离相等保留首轮实例），不存在则加入池中。
     * 合并池包含首轮 filterAndTruncate 输出的全部片段，补检只可能扩充池，不可能丢失首轮片段。
     */
    static List<ChunkSearchResult> dedup(List<ChunkSearchResult> firstRound, List<ChunkSearchResult> retryRound) {
        List<ChunkSearchResult> pool = new ArrayList<>(firstRound);
        for (ChunkSearchResult retryItem : retryRound) {
            int existingIndex = indexOfChunkId(pool, retryItem.chunkId());
            if (existingIndex < 0) {
                pool.add(retryItem);
            } else if (retryItem.distance() < pool.get(existingIndex).distance()) {
                // 同 chunkId 保留距离更小者；距离相等保留首轮（先入池，不动）
                pool.set(existingIndex, retryItem);
            }
        }
        return pool;
    }

    /**
     * 首轮保底配额合并（AC8b，spec 4.2.3）：
     * G = min(首轮片段数, ceil(capK/2)) 席保底给首轮；补检新增片段（chunkId 不在首轮列表中）
     * 按（距离, chunkId）升序取满剩余 capK - G 席；最终按（距离, chunkId）升序输出。
     *
     * @param firstRound 首轮 filterAndTruncate 输出（已按距离升序）
     * @param retryRound 补检轮 filterAndTruncate 输出（已按距离升序）
     * @param capK       单轮口径上限 K（KnowledgeRetrievalGrader.resolveSingleRoundCap 的唯一定义）
     */
    static List<ChunkSearchResult> mergeWithFirstRoundQuota(
            List<ChunkSearchResult> firstRound, List<ChunkSearchResult> retryRound, int capK) {
        // 补检新增：chunkId 不在首轮列表中的条目（同 chunkId 者不占新席位，首轮版本保留）
        Set<Long> firstRoundChunkIds = new HashSet<>();
        for (ChunkSearchResult item : firstRound) {
            firstRoundChunkIds.add(item.chunkId());
        }
        List<ChunkSearchResult> newFromRetry = retryRound.stream()
                .filter(item -> !firstRoundChunkIds.contains(item.chunkId()))
                .sorted(DISTANCE_THEN_CHUNK_ID)
                .toList();
        // 首轮保底席位：G = min(首轮片段数, ceil(capK/2))；ceilDiv 用整数运算
        int guaranteedSeats = Math.min(firstRound.size(), (capK + 1) / 2);
        List<ChunkSearchResult> result = new ArrayList<>(firstRound.subList(0, guaranteedSeats));
        int retrySeats = Math.min(capK - guaranteedSeats, newFromRetry.size());
        result.addAll(newFromRetry.subList(0, retrySeats));
        result.sort(DISTANCE_THEN_CHUNK_ID);
        return result;
    }

    // （距离升序, chunkId 升序）确定性排序，测试可复现
    private static final Comparator<ChunkSearchResult> DISTANCE_THEN_CHUNK_ID =
            Comparator.comparingDouble(ChunkSearchResult::distance)
                    .thenComparing(ChunkSearchResult::chunkId);

    private static int indexOfChunkId(List<ChunkSearchResult> pool, Long chunkId) {
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).chunkId().equals(chunkId)) {
                return i;
            }
        }
        return -1;
    }
}
