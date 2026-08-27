package com.wisread.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 用量记录表（usage_logs）的持久化实体。
 * 系统每次调用大模型（如生成回答、向量化）都会记录 token 消耗，
 * 用于用户维度的用量统计、成本核算与配额控制。
 */
@TableName("usage_logs")
public class UsageLog {

    @TableId(type = IdType.AUTO)
    private Long id; // 主键ID，自增

    private Long userId; // 产生该用量的用户ID（users.id）

    private String model; // 调用的模型名称，如 gpt-xxx 或某嵌入模型

    private Integer inputTokens; // 本次调用输入的 token 数量（提示词侧）

    private Integer outputTokens; // 本次调用输出的 token 数量（生成侧）

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt; // 记录创建时间，插入时自动填充

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
