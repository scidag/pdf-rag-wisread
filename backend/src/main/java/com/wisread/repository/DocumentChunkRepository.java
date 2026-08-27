package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档切片（DocumentChunk）的数据访问接口。
 * <p>
 * 负责访问 document_chunk 表，存储文档被向量化切分后的文本块及 pgvector 向量（用于 RAG 检索）。
 * 基于 MyBatis-Plus，继承自 {@link BaseRepository}。
 */
@Mapper
public interface DocumentChunkRepository extends BaseRepository<DocumentChunk> {

    /**
     * 根据文档 id 删除该文档下的全部切片（级联清理向量数据）。
     *
     * @param documentId 文档 id（外键关联 document 表）
     */
    default void deleteByDocumentId(Long documentId) {
        delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId));
    }

    /**
     * 统计指定文档下的切片数量。
     *
     * @param documentId 文档 id
     * @return 该文档的切片总数
     */
    default long countByDocumentId(Long documentId) {
        return selectCount(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId));
    }
}
