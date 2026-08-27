package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 用户会话表（user_sessions）的持久化实体。
 * 用于管理用户的登录态（Refresh Token），支持多设备登录、
 * Token 轮换（旧 Token 留存用于防重放）及登录审计（设备/IP/过期时间）。
 */
@TableName("user_sessions")
public class UserSession {

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private Long userId; // 关联用户ID（users.id）

    private String refreshTokenHash; // 当前刷新令牌的哈希值，用于换取新的访问令牌

    private String previousRefreshTokenHash; // 上一代刷新令牌哈希，令牌轮换期间用于检测重放攻击

    private String device; // 登录设备信息（如浏览器/系统标识）

    private String ipAddress; // 登录时的客户端 IP 地址，用于安全审计

    private Instant expiresAt; // 该会话（Refresh Token）的过期时间

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 创建时间，插入时自动填充

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRefreshTokenHash() {
        return refreshTokenHash;
    }

    public void setRefreshTokenHash(String refreshTokenHash) {
        this.refreshTokenHash = refreshTokenHash;
    }

    public String getPreviousRefreshTokenHash() {
        return previousRefreshTokenHash;
    }

    public void setPreviousRefreshTokenHash(String previousRefreshTokenHash) {
        this.previousRefreshTokenHash = previousRefreshTokenHash;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
