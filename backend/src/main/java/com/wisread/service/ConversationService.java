package com.wisread.service;

import com.wisread.dto.ConversationResponse;
import com.wisread.dto.CreateConversationRequest;
import com.wisread.dto.MessageResponse;
import com.wisread.entity.Conversation;

import java.util.List;

/**
 * 会话（Conversation）服务接口。
 *
 * <p>负责“智阅”RAG 系统中“会话”维度的管理：创建会话、按项目列举会话、
 * 读取某会话下的消息历史（含每条助手回答的引用来源），以及会话归属校验。
 * 会话是问答的上下文容器，隶属于某个项目，决定 RAG 检索的知识范围。
 */
public interface ConversationService {

    /**
     * 创建会话。
     *
     * @param userId  当前用户 ID
     * @param request 创建请求，含所属项目 ID 与可选标题
     * @return 新建会话的响应
     */
    ConversationResponse create(Long userId, CreateConversationRequest request);

    /**
     * 列出某项目下的全部会话（按最近更新时间倒序）。
     *
     * @param userId    当前用户 ID
     * @param projectId 所属项目 ID
     * @return 会话响应列表
     */
    List<ConversationResponse> list(Long userId, Long projectId);

    /**
     * 获取某会话的完整消息历史，并附带每条助手消息的引用来源。
     *
     * @param userId          当前用户 ID
     * @param conversationId  会话 ID
     * @return 消息响应列表（含来源）
     */
    List<MessageResponse> messages(Long userId, Long conversationId);

    /**
     * 校验会话归属当前用户（及项目），返回会话实体。
     *
     * @param userId          当前用户 ID
     * @param conversationId  会话 ID
     * @return 校验通过的会话实体
     */
    Conversation findOwnedConversation(Long userId, Long conversationId);
}
