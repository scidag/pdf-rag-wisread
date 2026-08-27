package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.UserSession;
import org.apache.ibatis.annotations.Mapper;

import java.time.Instant;
import java.util.Optional;

/**
 * 用户会话（UserSession）的数据访问接口。
 * <p>
 * 负责访问 user_session 表，存储用户的刷新令牌（refresh token）及其哈希与过期时间，用于登录态续期。
 * 查询以 refreshTokenHash 定位并校验过期时间，支持刷新令牌轮换（previousRefreshTokenHash）。
 * 基于 MyBatis-Plus，继承自 {@link BaseRepository}。
 */
@Mapper
public interface UserSessionRepository extends BaseRepository<UserSession> {

    /**
     * 根据刷新令牌哈希查询尚未过期的会话（用于令牌刷新校验）。
     *
     * @param refreshTokenHash 刷新令牌哈希值
     * @param now              当前时间，作为过期时间下界
     * @return 命中的有效会话 Optional；若不存在或已过期则为空
     */
    default Optional<UserSession> findByRefreshTokenHashAndExpiresAtAfter(String refreshTokenHash, Instant now) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getRefreshTokenHash, refreshTokenHash)
                .gt(UserSession::getExpiresAt, now)));
    }

    /**
     * 根据“上一代”刷新令牌哈希查询尚未过期的会话（支持刷新令牌轮换时的旧令牌校验/回收）。
     *
     * @param previousRefreshTokenHash 上一代刷新令牌哈希值
     * @param now                      当前时间，作为过期时间下界
     * @return 命中的有效会话 Optional；若不存在或已过期则为空
     */
    default Optional<UserSession> findByPreviousRefreshTokenHashAndExpiresAtAfter(String previousRefreshTokenHash, Instant now) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getPreviousRefreshTokenHash, previousRefreshTokenHash)
                .gt(UserSession::getExpiresAt, now)));
    }

    /**
     * 删除指定用户的全部会话（用于登出/注销时清理登录态）。
     *
     * @param userId 用户 id
     */
    default void deleteByUserId(Long userId) {
        delete(new LambdaQueryWrapper<UserSession>()
                .eq(UserSession::getUserId, userId));
    }
}
