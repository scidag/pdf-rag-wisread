package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.Message;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 消息（Message）的数据访问接口。
 * <p>
 * 负责访问 message 表，存储会话中的问答消息。查询均以 conversationId 限定归属，
 * 配合会话层的用户隔离，保证消息只能在其所属会话范围内访问。
 * 基于 MyBatis-Plus，继承自 {@link BaseRepository}。
 */
@Mapper
public interface MessageRepository extends BaseRepository<Message> {

    /**
     * 查询指定会话下的全部消息，按创建时间升序返回（即对话的自然时间顺序）。
     *
     * @param conversationId 会话 id（归属条件）
     * @return 该会话的消息列表，按 created_at 从早到晚排序
     */
    default List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId) {
        return selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getCreatedAt));
    }

    /**
     * 查询指定会话中最近的 10 条消息，按创建时间倒序返回（用于上下文裁剪/历史预览）。
     * 通过 {@code .last("LIMIT 10")} 在 SQL 末尾追加 LIMIT 限制返回条数。
     *
     * @param conversationId 会话 id（归属条件）
     * @return 该会话最近 10 条消息，按 created_at 从新到旧排序
     */
    default List<Message> findTop10ByConversationIdOrderByCreatedAtDesc(Long conversationId) {
        return selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getCreatedAt)
                .last("LIMIT 10"));
    }
}
