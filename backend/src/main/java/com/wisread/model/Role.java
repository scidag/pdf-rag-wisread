package com.wisread.model;

/**
 * 用户角色枚举（内存枚举，对应于 User 实体的 role 字段）。
 * 用于区分平台中的普通用户与超级管理员，控制权限与后台能力。
 */
public enum Role {
    USER, // 普通用户
    ADMIN // 管理员
}
