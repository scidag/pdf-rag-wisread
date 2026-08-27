package com.wisread.repository;

import com.wisread.entity.UsageLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用量日志（UsageLog）的数据访问接口。
 * <p>
 * 负责访问 usage_log 表，记录用户的 Token 消耗、调用次数等计费/统计用量信息。
 * 本接口仅继承 {@link BaseRepository}，未定义额外自定义方法，直接使用 MyBatis-Plus 通用 CRUD。
 * 基于 MyBatis-Plus。
 */
@Mapper
public interface UsageLogRepository extends BaseRepository<UsageLog> {
}
