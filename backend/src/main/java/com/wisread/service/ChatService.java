package com.wisread.service;

import com.wisread.dto.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答（Chat）服务接口。
 *
 * <p>这是“智阅”RAG（检索增强生成）系统的核心对话入口。负责接收用户提问，
 * 在指定会话（属于某项目）内完成“取历史→改写→向量检索→重排→生成→流式返回”的端到端编排。
 *
 * <p>注意：本服务以 SSE（Server-Sent Events）流式返回答案，调用方立即拿到一个
 * {@link SseEmitter} 句柄，真正的问答计算在异步线程中执行，逐 token 推送。
 */
public interface ChatService {

    /**
     * 发起一次问答请求。
     *
     * <p>做什么：校验会话归属后，立即返回一个 SSE 发射器；真正的检索与生成流程在后台异步执行，
     * 过程中以 {@code delta} 事件逐段推送文本，最后以 {@code done} 事件回传完整答案与引用来源。
     *
     * @param userId         当前用户 ID（用于权限校验与资源隔离）
     * @param conversationId 会话 ID（决定检索范围与上下文）
     * @param request        用户提问内容
     * @return SSE 发射器，前端据此接收流式响应
     */
    SseEmitter ask(Long userId, Long conversationId, ChatRequest request);
}
