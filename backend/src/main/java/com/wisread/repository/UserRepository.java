package com.wisread.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisread.entity.User;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface UserRepository extends BaseRepository<User> {

    default boolean existsByEmail(String email) {
        return selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)) > 0;
    }

    default boolean existsByUsername(String username) {
        return selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)) > 0;
    }

    default Optional<User> findByEmail(String email) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)));
    }
}
