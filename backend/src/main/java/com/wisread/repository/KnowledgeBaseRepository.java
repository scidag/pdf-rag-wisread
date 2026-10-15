package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库（KnowledgeBase）的数据访问接口。
 * <p>
 * 负责访问 knowledge_bases 表。知识库采用软删除机制（通过 deleted_at 字段标记删除），
 * 因此查询方法围绕 deleted_at 是否为空来区分“有效知识库”与“回收站知识库”，
 * 并统一携带 userId 实现用户隔离。基于 MyBatis-Plus，继承自 {@link BaseRepository}。
 */
@Mapper
public interface KnowledgeBaseRepository extends BaseRepository<KnowledgeBase> {

    /**
     * 查询指定用户的有效（未删除）知识库，按创建时间倒序返回。
     *
     * @param userId 用户 id（用户隔离条件）
     * @return 该用户未删除的知识库列表，按 created_at 从新到旧排序
     */
    default List<KnowledgeBase> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId) {
        return selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .isNull(KnowledgeBase::getDeletedAt)
                .orderByDesc(KnowledgeBase::getCreatedAt));
    }

    /**
     * 查询指定用户的已删除（回收站）知识库，按删除时间倒序返回。
     *
     * @param userId 用户 id（用户隔离条件）
     * @return 该用户已软删除的知识库列表，按 deleted_at 从新到旧排序
     */
    default List<KnowledgeBase> findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long userId) {
        return selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .isNotNull(KnowledgeBase::getDeletedAt)
                .orderByDesc(KnowledgeBase::getDeletedAt));
    }

    /**
     * 按用户与知识库 id 查询单条有效（未删除）知识库，实现用户隔离并排除软删除项。
     *
     * @param userId 用户 id
     * @param id     知识库 id
     * @return 命中的有效知识库 Optional；若不存在或已删除则为空
     */
    default Optional<KnowledgeBase> findByUserIdAndIdAndDeletedAtIsNull(Long userId, Long id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getId, id)
                .isNull(KnowledgeBase::getDeletedAt)));
    }

    /**
     * 按用户与知识库 id 查询单条已删除知识库（用于恢复场景）。
     *
     * @param userId 用户 id
     * @param id     知识库 id
     * @return 命中的已删除知识库 Optional；若不存在或仍有效则为空
     */
    default Optional<KnowledgeBase> findByUserIdAndIdAndDeletedAtIsNotNull(Long userId, Long id) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getId, id)
                .isNotNull(KnowledgeBase::getDeletedAt)));
    }

    /**
     * 统计指定用户的有效（未删除）知识库数量。
     *
     * @param userId 用户 id
     * @return 该用户未删除的知识库总数
     */
    default long countByUserIdAndDeletedAtIsNull(Long userId) {
        return selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .isNull(KnowledgeBase::getDeletedAt));
    }

    /**
     * 一次 SQL 返回用户全部知识库的文档数与分块数，供列表接口批量组装，消除 N+1。
     * 统计对象为知识库专属表 knowledge_documents / knowledge_chunks（见 V10），与项目 documents 无关。
     */
    @Select("""
            SELECT kb.id AS kb_id,
                   (SELECT COUNT(*) FROM knowledge_documents kd WHERE kd.knowledge_base_id = kb.id) AS document_count,
                   (SELECT COUNT(*) FROM knowledge_chunks kc
                      WHERE kc.knowledge_base_id = kb.id) AS chunk_count
            FROM knowledge_bases kb
            WHERE kb.user_id = #{userId}
            """)
    List<Map<String, Object>> countStatsByUserId(@Param("userId") Long userId);

    /**
     * 统计指定知识库下的文档数量（knowledge_documents 按 knowledge_base_id 计数）。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 该知识库下的文档总数
     */
    @Select("""
            SELECT COUNT(*) FROM knowledge_documents
            WHERE knowledge_base_id = #{knowledgeBaseId}
            """)
    long countDocumentsByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId);

    /**
     * 统计指定知识库下的分块数量（knowledge_chunks 按 knowledge_base_id 计数）。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 该知识库下的分块总数
     */
    @Select("""
            SELECT COUNT(*) FROM knowledge_chunks
            WHERE knowledge_base_id = #{knowledgeBaseId}
            """)
    long countChunksByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId);

    /**
     * 统计指定知识库下的文档状态分布（按 status 分组计数），供状态派生使用。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 每行包含 status（文档状态）与 cnt（该状态文档数）
     */
    @Select("""
            SELECT kd.status AS status, COUNT(*) AS cnt
            FROM knowledge_documents kd
            WHERE kd.knowledge_base_id = #{knowledgeBaseId}
            GROUP BY kd.status
            """)
    List<Map<String, Object>> countDocumentStatusByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId);
}
