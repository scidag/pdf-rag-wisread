package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.DocumentJob;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface DocumentJobRepository extends BaseRepository<DocumentJob> {

    default Optional<DocumentJob> findByDocumentId(Long documentId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<DocumentJob>()
                .eq(DocumentJob::getDocumentId, documentId)));
    }
}
