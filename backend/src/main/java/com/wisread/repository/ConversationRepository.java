package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/**
 * 会话（Conversation）的数据访问接口。
 * <p>
 * 负责访问 conversation 表，记录用户与系统之间的对话会话。所有查询均强制携带 userId，
 * 以保证用户隔离（每个用户只能访问自己的会话）。
 * 基于 MyBatis-Plus，继承自 {@link BaseRepository}。
 */
@Mapper
public interface ConversationRepository extends BaseRepository<Conversation> {

    /**
     * 按用户与会话 id 联合查询单条会话，实现用户隔离。
     *
     * @param userId          用户 id（用户隔离条件）
     * @param conversationId  会话 id
     * @return 命中的会话 Optional；若会话不存在或不属于该用户则为空
     */
    default Optional<Conversation> findByUserIdAndId(Long userId, Long conversationId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getId, conversationId)));
    }

    /**
     * 查询指定用户在某项目下的全部会话，按更新时间倒序返回（最近活跃的在前）。
     * 同时满足用户隔离与项目隔离条件。
     *
     * @param userId    用户 id（用户隔离条件）
     * @param projectId 项目 id（项目隔离条件）
     * @return 该用户在指定项目下的会话列表，按 updated_at 从新到旧排序
     */
    default List<Conversation> findByUserIdAndProjectIdOrderByUpdatedAtDesc(Long userId, Long projectId) {
        return selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getProjectId, projectId)
                .orderByDesc(Conversation::getUpdatedAt));
    }

    /**
     * 统计指定用户在某项目下的会话数量，直接 COUNT，不再查全列表。
     */
    default long countByUserIdAndProjectId(Long userId, Long projectId) {
        return selectCount(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getProjectId, projectId));
    }
}
