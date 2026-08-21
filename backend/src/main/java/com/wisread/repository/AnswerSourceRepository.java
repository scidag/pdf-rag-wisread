package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.AnswerSource;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AnswerSourceRepository extends BaseRepository<AnswerSource> {

    default List<AnswerSource> findByMessageIdOrderById(Long messageId) {
        return selectList(new LambdaQueryWrapper<AnswerSource>()
                .eq(AnswerSource::getMessageId, messageId)
                .orderByAsc(AnswerSource::getId));
    }
}
