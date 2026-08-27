package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.User;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/**
 * 用户（User）的数据访问接口。
 * <p>
 * 负责访问 user 表，提供用户注册/登录所需的基础查询：按邮箱、用户名判断是否存在与查询用户。
 * 基于 MyBatis-Plus，继承自 {@link BaseRepository}。
 */
@Mapper
public interface UserRepository extends BaseRepository<User> {

    /**
     * 判断是否存在指定邮箱的用户（用于注册查重与登录校验）。
     *
     * @param email 邮箱（唯一约束字段）
     * @return 存在返回 true，否则 false
     */
    default boolean existsByEmail(String email) {
        return selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)) > 0;
    }

    /**
     * 判断是否存在指定用户名的用户（用于注册查重）。
     *
     * @param username 用户名（唯一约束字段）
     * @return 存在返回 true，否则 false
     */
    default boolean existsByUsername(String username) {
        return selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)) > 0;
    }

    /**
     * 按邮箱查询用户（用于邮箱登录）。
     *
     * @param email 邮箱
     * @return 命中的用户 Optional；若不存在则为空
     */
    default Optional<User> findByEmail(String email) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)));
    }
}
