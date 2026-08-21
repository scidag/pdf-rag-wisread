package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ConversationRepository extends BaseRepository<Conversation> {

    default Optional<Conversation> findByUserIdAndId(Long userId, Long conversationId) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getId, conversationId)));
    }

    default List<Conversation> findByUserIdAndProjectIdOrderByUpdatedAtDesc(Long userId, Long projectId) {
        return selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getProjectId, projectId)
                .orderByDesc(Conversation::getUpdatedAt));
    }
}
