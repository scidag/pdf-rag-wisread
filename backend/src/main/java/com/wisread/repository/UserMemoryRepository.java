package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.UserMemory;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 长期用户记忆（UserMemory）的数据访问接口。
 * <p>
 * 基于 MyBatis-Plus，继承自 {@link BaseRepository}。所有查询方法均以 userId 限定归属，
 * 保证跨用户隔离（越权场景不返回数据）。向量列（embedding）的写入与检索由
 * {@code UserMemoryServiceImpl} 通过 JdbcTemplate 完成，不经由本接口。
 */
@Mapper
public interface UserMemoryRepository extends BaseRepository<UserMemory> {

    /**
     * 查询该用户全部 ACTIVE 状态的记忆，按创建时间倒序（新记忆在前）。
     */
    default List<UserMemory> findActiveByUserId(Long userId) {
        return selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getStatus, "ACTIVE")
                .orderByDesc(UserMemory::getCreatedAt));
    }

    /**
     * 统计该用户 ACTIVE 记忆条数（超限淘汰用）。
     */
    default long countActiveByUserId(Long userId) {
        return selectCount(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getStatus, "ACTIVE"));
    }

    /**
     * 取该用户最早创建、重要性最低的 n 条 ACTIVE 记忆（淘汰候选项）。
     */
    default List<UserMemory> findEvictionCandidates(Long userId, int limit) {
        return selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getStatus, "ACTIVE")
                .orderByAsc(UserMemory::getImportance)
                .orderByAsc(UserMemory::getCreatedAt)
                .last("LIMIT " + limit));
    }
}
