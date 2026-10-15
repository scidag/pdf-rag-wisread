package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 知识库文档（KnowledgeDocument）的数据访问接口。
 * <p>
 * 负责访问 knowledge_documents 表（见 V10 迁移），提供按用户/知识库维度的查询、
 * 处理状态的 CAS 抢占与卡死任务扫描。所有查询显式携带 userId 实现用户隔离。
 * 基于 MyBatis-Plus，继承自 {@link BaseRepository}。
 */
@Mapper
public interface KnowledgeDocumentRepository extends BaseRepository<KnowledgeDocument> {

    /**
     * 按用户与文档 id 联合查询单条知识库文档，实现用户隔离。
     *
     * @param userId 用户 id（用户隔离条件）
     * @param id     文档 id
     * @return 命中的文档 Optional；若文档不存在或不属于该用户则为空
     */
    default Optional<KnowledgeDocument> findByUserIdAndId(Long userId, Long id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getUserId, userId)
                .eq(KnowledgeDocument::getId, id)));
    }

    /**
     * 查询指定知识库下的全部文档，按创建时间倒序返回。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 该知识库下的文档列表，按 created_at 从新到旧排序
     */
    default List<KnowledgeDocument> findByKnowledgeBaseIdOrderByCreatedAtDesc(Long knowledgeBaseId) {
        return selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(KnowledgeDocument::getCreatedAt));
    }

    /**
     * 扫描处于 PROCESSING 且 updated_at 早于阈值的知识库文档（疑似卡死任务），
     * 供定时恢复任务重新调度或置为失败。
     *
     * @param status     目标状态（通常传 PROCESSING）
     * @param threshold  更新时间阈值，早于该时间视为卡死
     * @return 卡死文档列表
     */
    default List<KnowledgeDocument> findByStatusAndUpdatedAtBefore(String status, Instant threshold) {
        return selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getStatus, status)
                .lt(KnowledgeDocument::getUpdatedAt, threshold));
    }

    /**
     * CAS 抢占处理权：仅当文档仍处于 UPLOADED 时原子置为 PROCESSING。
     * 为什么用条件 UPDATE：异步处理可能被上传触发、重试、定时恢复多处并发调用，
     * 以数据库行为锁保证同一文档同一时刻只有一个线程在处理，返回受影响行数为 0 即抢占失败。
     *
     * @param id 文档 id
     * @return 受影响行数：1 表示抢占成功，0 表示状态已不是 UPLOADED（他人已接管）
     */
    @Update("""
            UPDATE knowledge_documents
            SET status = 'PROCESSING', error_message = NULL, updated_at = now()
            WHERE id = #{id} AND status = 'UPLOADED'
            """)
    int claimProcessing(@Param("id") Long id);
}
