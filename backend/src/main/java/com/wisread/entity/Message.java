package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 消息表（messages）的持久化实体。
 * 会话（Conversation）中的每一条对话内容都是一条 Message，
 * 既包含用户的提问，也包含系统的回答（含引用来源），按 role 区分说话方。
 */
@TableName("messages")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private Long conversationId; // 所属会话ID（conversations.id）

    private String role; // 消息角色，如 user(用户提问)、assistant(系统回答)，对应 model.Role 的取值

    private String content; // 消息文本内容（用户问题或模型回答）

    private String status; // 消息状态，如 streaming(生成中)、completed(完成)、failed(失败)

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 创建时间，插入时自动填充

    public Long getId() {
        return id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
