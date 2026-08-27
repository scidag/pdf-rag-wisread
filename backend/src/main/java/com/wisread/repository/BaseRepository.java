package com.wisread.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.Optional;

/**
 * 通用 Repository 基类。
 * <p>
 * 基于 MyBatis-Plus 的 {@code BaseMapper<T>} 进行扩展，为所有业务 Repository 提供统一的
 * 基础数据访问能力。所有具体实体 Repository（如 DocumentRepository、UserRepository 等）均继承本接口，
 * 从而自动获得 MyBatis-Plus 内置的 CRUD 方法（selectById、insert、updateById、delete 等）。
 * <p>
 * 本基类在 MyBatis-Plus 之上额外封装了一个 {@code findById} 默认方法，将 {@code selectById}
 * 的返回值包装为 {@link Optional}，便于上层调用时进行空值安全的处理。
 *
 * @param <T> 实体类型，对应数据库中的一张表
 */
public interface BaseRepository<T> extends BaseMapper<T> {

    /**
     * 根据主键 id 查询单条记录，并以 {@link Optional} 包装结果。
     * <p>
     * 底层调用 MyBatis-Plus 的 {@code selectById}，当记录不存在时返回 {@link Optional#empty()}，
     * 调用方无需再做 null 判断。
     *
     * @param id 记录主键
     * @return 包含目标实体的 Optional；若记录不存在则为空 Optional
     */
    default Optional<T> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }
}
