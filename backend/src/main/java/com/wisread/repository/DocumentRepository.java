package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.Document;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface DocumentRepository extends BaseRepository<Document> {

    default Optional<Document> findByUserIdAndId(Long userId, Long id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .eq(Document::getId, id)));
    }

    default List<Document> findByUserIdOrderByCreatedAtDesc(Long userId) {
        return selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .orderByDesc(Document::getCreatedAt));
    }

    default List<Document> findByProjectIdOrderByCreatedAtDesc(Long projectId) {
        return selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getProjectId, projectId)
                .orderByDesc(Document::getCreatedAt));
    }

    default Optional<Document> findByUserIdAndProjectIdAndId(Long userId, Long projectId, Long id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .eq(Document::getProjectId, projectId)
                .eq(Document::getId, id)));
    }

    default long countByUserId(Long userId) {
        return selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId));
    }

    default long countByProjectId(Long projectId) {
        return selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getProjectId, projectId));
    }

    default long countByUserIdAndProjectId(Long userId, Long projectId) {
        return selectCount(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .eq(Document::getProjectId, projectId));
    }
}
