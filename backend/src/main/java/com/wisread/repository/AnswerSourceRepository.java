package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.AnswerSource;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 答案引用来源（AnswerSource）的数据访问接口。
 * <p>
 * 负责访问 answer_source 表，记录每条 AI 回答所引用的文档切片来源（用于回答溯源/引用展示）。
 * 基于 MyBatis-Plus，继承自 {@link BaseRepository}，拥有通用 CRUD 能力。
 */
@Mapper
public interface AnswerSourceRepository extends BaseRepository<AnswerSource> {

    /**
     * 根据消息 id 查询该消息对应的全部答案引用来源，按 id 升序返回。
     *
     * @param messageId 消息 id（外键关联 message 表）
     * @return 该消息关联的所有引用来源列表，按 id 从小到大排序；无结果时返回空列表
     */
    default List<AnswerSource> findByMessageIdOrderById(Long messageId) {
        return selectList(new LambdaQueryWrapper<AnswerSource>()
                .eq(AnswerSource::getMessageId, messageId)
                .orderByAsc(AnswerSource::getId));
    }
}
