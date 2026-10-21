package com.wisread.service;

import com.wisread.entity.Message;
import com.wisread.model.RetryRewriteResult;

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

    /**
     * 补检索改写（Agentic RAG 阶段 1，spec 4.2.5）：为"补检索"换一个检索角度生成新检索词。
     *
     * <p>输入是首轮改写后的独立问题（已含指代消解、自包含），刻意不带历史——
     * 补检索必须与首轮语义同源，勿改为用户原始问题。
     *
     * <p>结果状态机：REWRITTEN（有效新检索词，继续补检）/ PASSTHROUGH（LLM 失败，直接结束补检）
     * / INVALID（空白或与输入相同，直接结束补检，不重复执行相同查询）。
     *
     * <p>计量边界：调用完成即计量（含结果判 INVALID 的场景）；调用异常未完成则不计量。
     *
     * @param question 首轮改写后的独立问题
     * @param userId   当前用户 ID（用于用量记录）
     * @return 改写结果状态机
     */
    RetryRewriteResult rewriteForRetry(String question, Long userId);
}
