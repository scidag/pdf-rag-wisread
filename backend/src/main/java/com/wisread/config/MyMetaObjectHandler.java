package com.wisread.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * MyBatis-Plus 自动填充处理器。
 * 负责在数据插入/更新时自动写入审计时间字段，避免业务代码重复设置。
 * 配合实体类中 {@code @TableField(fill = ...)} 注解的字段生效。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时自动填充。
     * 将 {@code createdAt} 与 {@code updatedAt} 设为当前时间，
     * 保证记录创建时间一致，且首次创建时更新时间同样被赋值。
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = Instant.now();
        strictInsertFill(metaObject, "createdAt", Instant.class, now);
        strictInsertFill(metaObject, "updatedAt", Instant.class, now);
    }

    /**
     * 更新时自动填充。
     * 仅刷新 {@code updatedAt} 为当前时间，标记记录的最近修改时刻。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updatedAt", Instant.now(), metaObject);
    }
}
