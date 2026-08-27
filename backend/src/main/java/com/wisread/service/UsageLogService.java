package com.wisread.service;

/**
 * 用量计费日志服务接口。
 * 职责：记录每一次 AI 调用（Embedding、对话生成等）的 token 消耗与所用模型，
 * 为按用户、按模型统计成本与配额管理提供数据来源。写入失败不得影响主流程。
 */
public interface UsageLogService {

    /**
     * 记录一条用量日志。
     *
     * @param userId       调用用户 ID
     * @param model        实际使用的模型名称
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     */
    void log(Long userId, String model, int inputTokens, int outputTokens);
}
