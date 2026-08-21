package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.Message;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MessageRepository extends BaseRepository<Message> {

    default List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId) {
        return selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getCreatedAt));
    }

    default List<Message> findTop10ByConversationIdOrderByCreatedAtDesc(Long conversationId) {
        return selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getCreatedAt)
                .last("LIMIT 10"));
    }
}
