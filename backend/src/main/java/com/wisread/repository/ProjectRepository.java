package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.Project;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/**
 * 项目（Project）的数据访问接口。
 * <p>
 * 负责访问 project 表，记录用户创建的文档问答项目。项目采用软删除机制
 * （通过 deleted_at 字段标记删除，而非物理删除），因此查询方法围绕 deleted_at 是否为空
 * 来区分“有效项目”与“已删除项目”，并统一携带 userId 实现用户隔离。
 * 基于 MyBatis-Plus，继承自 {@link BaseRepository}。
 */
@Mapper
public interface ProjectRepository extends BaseRepository<Project> {

    /**
     * 查询指定用户的有效（未删除）项目，按创建时间倒序返回。
     *
     * @param userId 用户 id（用户隔离条件）
     * @return 该用户未删除的项目列表，按 created_at 从新到旧排序
     */
    default List<Project> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId) {
        return selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .isNull(Project::getDeletedAt)
                .orderByDesc(Project::getCreatedAt));
    }

    /**
     * 查询指定用户的已删除（回收站）项目，按删除时间倒序返回。
     *
     * @param userId 用户 id（用户隔离条件）
     * @return 该用户已软删除的项目列表，按 deleted_at 从新到旧排序
     */
    default List<Project> findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long userId) {
        return selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .isNotNull(Project::getDeletedAt)
                .orderByDesc(Project::getDeletedAt));
    }

    /**
     * 按用户与项目 id 查询单条有效（未删除）项目，实现用户隔离并排除软删除项。
     *
     * @param userId 用户 id
     * @param id     项目 id
     * @return 命中的有效项目 Optional；若不存在或已删除则为空
     */
    default Optional<Project> findByUserIdAndIdAndDeletedAtIsNull(Long userId, Long id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .eq(Project::getId, id)
                .isNull(Project::getDeletedAt)));
    }

    /**
     * 按用户与项目 id 查询单条已删除项目（用于恢复/彻底删除场景）。
     *
     * @param userId 用户 id
     * @param id     项目 id
     * @return 命中的已删除项目 Optional；若不存在或仍有效则为空
     */
    default Optional<Project> findByUserIdAndIdAndDeletedAtIsNotNull(Long userId, Long id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .eq(Project::getId, id)
                .isNotNull(Project::getDeletedAt)));
    }

    /**
     * 统计指定用户的有效（未删除）项目数量。
     *
     * @param userId 用户 id
     * @return 该用户未删除的项目总数
     */
    default long countByUserIdAndDeletedAtIsNull(Long userId) {
        return selectCount(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .isNull(Project::getDeletedAt));
    }
}
