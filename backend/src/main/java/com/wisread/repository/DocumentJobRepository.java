package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.DocumentJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 文档处理任务（DocumentJob）的数据访问接口。
 * <p>
 * 负责访问 document_job 表，记录文档的异步处理任务（解析、切片、向量化等）及其状态。
 * 基于 MyBatis-Plus，继承自 {@link BaseRepository}。
 */
@Mapper
public interface DocumentJobRepository extends BaseRepository<DocumentJob> {

    /**
     * 根据文档 id 查询其对应的处理任务（一个文档通常对应一个任务）。
     *
     * @param documentId 文档 id（外键关联 document 表）
     * @return 命中的处理任务 Optional；若任务不存在则为空
     */
    default Optional<DocumentJob> findByDocumentId(Long documentId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<DocumentJob>()
                .eq(DocumentJob::getDocumentId, documentId)));
    }

    /**
     * 原子抢占任务：仅当任务仍为 PENDING 时置为 RUNNING，避免并发重复处理。
     */
    @Update("""
            UPDATE document_jobs
            SET status = 'RUNNING', started_at = now(), updated_at = now()
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int claimPending(@Param("id") Long id);

    /**
     * 查找长时间未开始的 PENDING 任务（用于定时回收）。
     */
    default List<DocumentJob> findPendingOlderThan(Instant before) {
        return selectList(new LambdaQueryWrapper<DocumentJob>()
                .eq(DocumentJob::getStatus, "PENDING")
                .lt(DocumentJob::getCreatedAt, before));
    }

    /**
     * 查找执行超时的 RUNNING 任务（用于超时恢复）。
     */
    default List<DocumentJob> findRunningOlderThan(Instant before) {
        return selectList(new LambdaQueryWrapper<DocumentJob>()
                .eq(DocumentJob::getStatus, "RUNNING")
                .lt(DocumentJob::getStartedAt, before));
    }
}
