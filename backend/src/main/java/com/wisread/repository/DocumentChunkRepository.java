package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentChunkRepository extends BaseRepository<DocumentChunk> {

    default void deleteByDocumentId(Long documentId) {
        delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId));
    }

    default long countByDocumentId(Long documentId) {
        return selectCount(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId));
    }
}
