package com.wisread.repository;

import com.wisread.entity.UsageLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UsageLogRepository extends BaseRepository<UsageLog> {
}
