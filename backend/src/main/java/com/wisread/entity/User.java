package com.wisread.entity;

import com.wisread.model.Role;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 用户表（users）的持久化实体。
 * 平台注册用户，保存账号基本信息、密码哈希与角色权限，
 * 关联其上传的文档、创建的会话及用量记录。
 */
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private String username; // 用户名，登录标识

    private String email; // 邮箱，可用于登录或通知

    private String passwordHash; // 密码的哈希值（加盐），不存储明文

    private String avatarUrl; // 头像图片地址

    private Short status = 1; // 账号状态：1 表示正常启用，0 表示禁用

    private Role role = Role.USER; // 用户角色，见 model.Role 枚举（USER / ADMIN）

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 创建时间，插入时自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt; // 更新时间，插入与每次更新时自动填充

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public Short getStatus() {
        return status;
    }

    public void setStatus(Short status) {
        this.status = status;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
