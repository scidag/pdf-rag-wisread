package com.wisread.service;

/**
 * 长期记忆提取服务接口：问答结束后异步沉淀用户记忆。
 * 内部完成前置过滤（长度/敏感黑名单/意图词）→ LLM 提取（JSON）→ 解析入库（含去重），
 * 全程尽力而为：任何失败只记日志，绝不影响问答主流程。
 */
public interface UserMemoryExtractionService {

    /**
     * 异步提取：从一轮问答（用户提问 + 助手回答）中提取值得长期记住的用户信息。
     *
     * @param userId           用户 ID
     * @param conversationId   会话 ID（记忆来源溯源用）
     * @param userMessage      用户原始提问（非改写后的检索 query）
     * @param assistantMessage 助手最终回答全文
     */
    void extractAsync(Long userId, Long conversationId, String userMessage, String assistantMessage);
}
