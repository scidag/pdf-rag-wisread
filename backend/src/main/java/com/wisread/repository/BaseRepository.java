package com.wisread.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.Optional;

public interface BaseRepository<T> extends BaseMapper<T> {

    default Optional<T> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }
}
