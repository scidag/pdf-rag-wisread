package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.Document;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/**
 * 文档（Document）的数据访问接口。
 * <p>
 * 负责访问 document 表，提供文档的通用 CRUD，以及按用户、按项目的查询与计数。
 * 涉及用户隔离与项目隔离的查询均显式携带 userId / projectId 条件。
 * 基于 MyBatis-Plus，继承自 {@link BaseRepository}。
 */
@Mapper
public interface DocumentRepository extends BaseRepository<Document> {

    /**
     * 按用户与文档 id 联合查询单条文档，实现用户隔离。
     *
     * @param userId 用户 id（用户隔离条件）
     * @param id     文档 id
     * @return 命中的文档 Optional；若文档不存在或不属于该用户则为空
     */
    default Optional<Document> findByUserIdAndId(Long userId, Long id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .eq(Document::getId, id)));
    }

    /**
     * 查询指定用户的全部文档，按创建时间倒序返回。
     *
     * @param userId 用户 id（用户隔离条件）
     * @return 该用户的文档列表，按 created_at 从新到旧排序
     */
    default List<Document> findByUserIdOrderByCreatedAtDesc(Long userId) {
        return selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .orderByDesc(Document::getCreatedAt));
    }

    /**
     * 查询指定项目下的全部文档，按创建时间倒序返回（项目隔离）。
     *
     * @param projectId 项目 id（项目隔离条件）
     * @return 该项目下的文档列表，按 created_at 从新到旧排序
     */
    default List<Document> findByProjectIdOrderByCreatedAtDesc(Long projectId) {
        return selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getProjectId, projectId)
                .orderByDesc(Document::getCreatedAt));
    }

    /**
     * 按用户、项目、文档 id 三者联合查询单条文档，同时满足用户隔离与项目隔离。
     *
     * @param userId    用户 id（用户隔离条件）
     * @param projectId 项目 id（项目隔离条件）
     * @param id        文档 id
     * @return 命中的文档 Optional；任意条件不匹配则为空
     */
    default Optional<Document> findByUserIdAndProjectIdAndId(Long userId, Long projectId, Long id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .eq(Document::getProjectId, projectId)
                .eq(Document::getId, id)));
    }

    /**
     * 统计指定用户拥有的文档数量。
     *
     * @param userId 用户 id（用户隔离条件）
     * @return 该用户的文档总数
     */
    default long countByUserId(Long userId) {
        return selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId));
    }

    /**
     * 统计指定项目下的文档数量（项目隔离）。
     *
     * @param projectId 项目 id
     * @return 该项目的文档总数
     */
    default long countByProjectId(Long projectId) {
        return selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getProjectId, projectId));
    }

    /**
     * 统计指定用户在指定项目下的文档数量，同时满足用户隔离与项目隔离。
     *
     * @param userId    用户 id
     * @param projectId 项目 id
     * @return 该用户在该项目下的文档总数
     */
    default long countByUserIdAndProjectId(Long userId, Long projectId) {
        return selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .eq(Document::getProjectId, projectId));
    }
}
