package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.Project;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ProjectRepository extends BaseRepository<Project> {

    default List<Project> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId) {
        return selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .isNull(Project::getDeletedAt)
                .orderByDesc(Project::getCreatedAt));
    }

    default List<Project> findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long userId) {
        return selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .isNotNull(Project::getDeletedAt)
                .orderByDesc(Project::getDeletedAt));
    }

    default Optional<Project> findByUserIdAndIdAndDeletedAtIsNull(Long userId, Long id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .eq(Project::getId, id)
                .isNull(Project::getDeletedAt)));
    }

    default Optional<Project> findByUserIdAndIdAndDeletedAtIsNotNull(Long userId, Long id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .eq(Project::getId, id)
                .isNotNull(Project::getDeletedAt)));
    }

    default long countByUserIdAndDeletedAtIsNull(Long userId) {
        return selectCount(new LambdaQueryWrapper<Project>()
                .eq(Project::getUserId, userId)
                .isNull(Project::getDeletedAt));
    }
}
