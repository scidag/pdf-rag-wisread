package com.wisread.service;

import com.wisread.entity.Message;

import java.util.List;

/**
 * 问题改写服务接口（QueryRewriteService）。
 *
 * <p>在 RAG 多轮对话中，用户当前提问常含指代（如“它”“这个方法”），直接拿去向量检索会召回不准。
 * 本服务利用历史对话，把当前问题改写为一个语义完整的独立问题，从而提升检索命中率。
 * 约定：仅当存在历史时才调用 LLM 改写，否则原样返回当前问题。
 */
public interface QueryRewriteService {

    /**
     * 改写用户问题。
     *
     * @param question 用户当前原始提问
     * @param history  历史消息列表（用于提供上下文）
     * @param userId   当前用户 ID（用于用量记录与隔离）
     * @return 改写后的独立完整问题（无历史时返回原问题）
     */
    String rewrite(String question, List<Message> history, Long userId);
}
