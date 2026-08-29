package com.wisread.repository;

import com.wisread.entity.ChatLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatLogRepository extends BaseRepository<ChatLog> {
}
