package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 项目表（projects）的持久化实体。
 * 项目（Project）是用户组织文档与问答的知识库单元，每个项目归属于一个用户，
 * 可包含多份文档与多段会话，并支持软删除（通过 deletedAt 标记）。
 */
@TableName("projects")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private Long userId; // 项目拥有者ID（users.id）

    private String name; // 项目名称

    private String description; // 项目描述

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 创建时间，插入时自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt; // 更新时间，插入与每次更新时自动填充

    private Instant deletedAt; // 软删除时间，非空表示该项目的逻辑删除时间

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
