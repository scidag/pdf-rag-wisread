package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.UserSession;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.Optional;

@Mapper
public interface UserSessionRepository extends BaseRepository<UserSession> {

    default Optional<UserSession> findByRefreshTokenHashAndExpiresAtAfter(String refreshTokenHash, Instant now) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getRefreshTokenHash, refreshTokenHash)
                .gt(UserSession::getExpiresAt, now)));
    }

    default Optional<UserSession> findByPreviousRefreshTokenHashAndExpiresAtAfter(String previousRefreshTokenHash, Instant now) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getPreviousRefreshTokenHash, previousRefreshTokenHash)
                .gt(UserSession::getExpiresAt, now)));
    }

    default void deleteByUserId(Long userId) {
        delete(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getUserId, userId));
    }
}
