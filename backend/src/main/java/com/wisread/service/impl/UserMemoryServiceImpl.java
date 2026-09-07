package com.wisread.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.UserMemory;
import com.wisread.exception.ApiException;
import com.wisread.repository.UserMemoryRepository;
import com.wisread.service.EmbeddingService;
import com.wisread.service.UserMemoryService;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 长期用户记忆服务实现。
 *
 * <p>实现要点：
 * <ul>
 *   <li>向量写入与检索走 JdbcTemplate + pgvector 字面量（对齐 {@code VectorIndexingServiceImpl} 的做法），
 *       生命周期查询走 MyBatis-Plus（{@code UserMemoryRepository}）。</li>
 *   <li>检索强制 user_id = ? 且 status='ACTIVE'（多租户隔离），相似度低于注入阈值的不返回。</li>
 *   <li>save 内置去重（相似度 ≥ dedup 阈值视为同一条信息，旧记忆置 SUPERSEDED）与超限淘汰
 *       （按 importance 升序 + created_at 升序置 DELETED），防止单用户记忆无限膨胀。</li>
 * </ul>
 */
@Service
public class UserMemoryServiceImpl implements UserMemoryService {

    private static final String INSERT_SQL = """
            INSERT INTO user_memory
                (user_id, content, category, importance, status, source_conversation_id, embedding, embedding_model_version)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
            """;

    private static final String SEARCH_SQL = """
            SELECT id, content, category, importance, status, source_conversation_id, created_at,
                   1 - (embedding <=> CAST(? AS vector)) AS similarity
            FROM user_memory
            WHERE user_id = ?
              AND status = 'ACTIVE'
              AND embedding IS NOT NULL
            ORDER BY embedding <=> CAST(? AS vector)
            LIMIT ?
            """;

    private final UserMemoryRepository userMemoryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final String embeddingModelVersion;

    @Value("${wisread.user-memory.inject-min-similarity:0.55}")
    private double injectMinSimilarity;

    @Value("${wisread.user-memory.dedup-similarity:0.92}")
    private double dedupSimilarity;

    @Value("${wisread.user-memory.max-memories-per-user:200}")
    private int maxMemoriesPerUser;

    public UserMemoryServiceImpl(
            UserMemoryRepository userMemoryRepository,
            JdbcTemplate jdbcTemplate,
            EmbeddingService embeddingService,
            @Value("${spring.ai.dashscope.embedding.options.model:qwen3.7-text-embedding-flash}") String embeddingModelVersion
    ) {
        this.userMemoryRepository = userMemoryRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.embeddingModelVersion = embeddingModelVersion;
    }

    /**
     * 向量检索该用户的活跃记忆。
     * 为什么复用问答链路的 query 向量：改写后的问题已完整表达意图，直接复用可省一次 Embedding 调用。
     */
    public List<UserMemory> search(Long userId, float[] queryEmbedding, int topK) {
        return searchHits(userId, queryEmbedding, topK).stream()
                .filter(hit -> hit.similarity() >= injectMinSimilarity)
                .map(MemoryHit::memory)
                .toList();
    }

    /**
     * 新增记忆：向量化 → 去重（替代旧记忆）→ 插入 → 超限淘汰。
     */
    public void save(Long userId, String content, String category, int importance, Long sourceConversationId) {
        float[] embedding = embeddingService.embed(List.of(content), userId).get(0);
        // 去重：与最相似的现有 ACTIVE 记忆比对，命中阈值说明是同一条信息的重复/更新
        List<MemoryHit> nearest = searchHits(userId, embedding, 1);
        if (!nearest.isEmpty() && nearest.get(0).similarity() >= dedupSimilarity) {
            supersede(userId, nearest.get(0).memory().getId());
        }
        jdbcTemplate.update(
                INSERT_SQL,
                userId,
                content,
                category,
                importance,
                sourceConversationId,
                toVector(embedding),
                embeddingModelVersion
        );
        evictOverLimit(userId);
    }

    /**
     * 将旧记忆置为 SUPERSEDED（保留原内容供审计），归属不符返回 404。
     */
    public void supersede(Long userId, Long oldId) {
        UserMemory memory = findOwned(userId, oldId);
        memory.setStatus("SUPERSEDED");
        userMemoryRepository.updateById(memory);
    }

    /**
     * 用户记忆管理列表（仅 ACTIVE）。
     */
    public List<UserMemory> listActive(Long userId) {
        return userMemoryRepository.findActiveByUserId(userId);
    }

    /**
     * 删除记忆（软删 DELETED），归属不符返回 404。
     */
    public void delete(Long userId, Long id) {
        UserMemory memory = findOwned(userId, id);
        memory.setStatus("DELETED");
        userMemoryRepository.updateById(memory);
    }

    /**
     * 超限淘汰：ACTIVE 记忆数超过上限时，按 importance 升序 + created_at 升序置 DELETED。
     * 为什么软删：保留审计痕迹，与用户删除行为同路径处理。
     */
    private void evictOverLimit(long userId) {
        long active = userMemoryRepository.countActiveByUserId(userId);
        int overflow = (int) (active - maxMemoriesPerUser);
        if (overflow <= 0) {
            return;
        }
        List<UserMemory> candidates = userMemoryRepository.findEvictionCandidates(userId, overflow);
        for (UserMemory candidate : candidates) {
            candidate.setStatus("DELETED");
            userMemoryRepository.updateById(candidate);
        }
    }

    /**
     * 校验记忆归属当前用户，不存在或越权统一 404（不暴露他人数据存在性）。
     */
    private UserMemory findOwned(Long userId, Long id) {
        UserMemory memory = userMemoryRepository.selectOne(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getId, id)
                .eq(UserMemory::getUserId, userId));
        if (memory == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "memory not found");
        }
        return memory;
    }

    /**
     * 向量检索底层实现：返回带相似度的命中项（供注入阈值过滤与去重比对）。
     */
    private List<MemoryHit> searchHits(long userId, float[] queryEmbedding, int topK) {
        PGobject vector = toVector(queryEmbedding);
        return jdbcTemplate.query(
                SEARCH_SQL,
                (resultSet, rowNum) -> {
                    // 检索结果仅需 id 与 content（注入拼接 / 去重替代），其余字段不回填
                    UserMemory memory = new UserMemory();
                    memory.setId(resultSet.getLong("id"));
                    memory.setContent(resultSet.getString("content"));
                    return new MemoryHit(memory, resultSet.getDouble("similarity"));
                },
                vector,
                userId,
                // ORDER BY 中的 CAST(? AS vector) 也需绑定同一向量（SQL 共 4 个占位符）
                vector,
                topK
        );
    }

    /**
     * 检索命中项：记忆对象 + 余弦相似度（1 - 距离）。
     */
    private record MemoryHit(UserMemory memory, double similarity) {
    }

    /**
     * 将 float[] 转换为 pgvector 向量字面量（PGobject 绑定，避免拼接注入），
     * 与 {@code VectorIndexingServiceImpl#toVector} 同构。
     */
    private PGobject toVector(float[] values) {
        String literal = IntStream.range(0, values.length)
                .mapToObj(index -> String.valueOf(values[index]))
                .collect(Collectors.joining(",", "[", "]"));
        try {
            PGobject vector = new PGobject();
            vector.setType("vector");
            vector.setValue(literal);
            return vector;
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to build pgvector literal", exception);
        }
    }
}
