package com.wisread.service;

import com.wisread.entity.UserMemory;

import java.util.List;

/**
 * 长期用户记忆服务接口。
 * 职责：用户画像/偏好/事实记忆的写入（含向量化与去重）、向量检索、生命周期管理。
 * 所有方法必须带 userId 过滤，越权返回 404（对齐 AGENT.md 约定）。
 */
public interface UserMemoryService {

    /**
     * 按向量检索该用户的 ACTIVE 记忆，仅返回相似度不低于
     * {@code wisread.user-memory.inject-min-similarity} 的条目（不相关记忆宁可不注入）。
     *
     * @param userId         用户 ID（隔离条件）
     * @param queryEmbedding 查询向量（复用问答链路中改写后 query 的向量，不额外调用 Embedding）
     * @param topK           最多返回条数
     */
    List<UserMemory> search(Long userId, float[] queryEmbedding, int topK);

    /**
     * 新增一条记忆：先向量化，再与现有 ACTIVE 记忆去重——
     * 相似度 ≥ {@code wisread.user-memory.dedup-similarity} 视为同一条信息，
     * 旧记忆置 SUPERSEDED 后写入新记忆；最后执行超限淘汰（超过
     * {@code wisread.user-memory.max-memories-per-user} 时按重要性+时间淘汰）。
     * 向量化消耗由 {@link EmbeddingService} 记入 usage_logs。
     */
    void save(Long userId, String content, String category, int importance, Long sourceConversationId);

    /**
     * 将旧记忆置为 SUPERSEDED（被新信息替代，保留审计痕迹）。
     * 必须校验归属，越权返回 404。
     */
    void supersede(Long userId, Long oldId);

    /**
     * 用户记忆管理列表（仅 ACTIVE，新记忆在前）。
     */
    List<UserMemory> listActive(Long userId);

    /**
     * 删除记忆（软删 DELETED）。必须校验归属，越权返回 404。
     */
    void delete(Long userId, Long id);
}
