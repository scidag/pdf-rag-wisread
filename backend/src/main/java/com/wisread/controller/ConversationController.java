package com.wisread.controller;

import com.wisread.dto.ChatRequest;
import com.wisread.dto.ConversationResponse;
import com.wisread.dto.CreateConversationRequest;
import com.wisread.dto.MessageResponse;
import com.wisread.service.ChatService;
import com.wisread.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 会话（Conversation）控制器。
 * 负责对话会话与消息相关的 REST 端点，包括创建会话、列出会话、查看消息，
 * 以及基于 RAG 的流式问答（SSE）。所有接口均需登录（从 Authentication 获取当前用户 ID）。
 * 基础路径：/api/v1/conversations
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatService chatService;

    public ConversationController(
            ConversationService conversationService,
            ChatService chatService
    ) {
        this.conversationService = conversationService;
        this.chatService = chatService;
    }

    /** POST /api/v1/conversations：创建新的对话会话。
     * 入参：@Valid CreateConversationRequest（所属项目 ID、可选标题），当前登录用户由 Authentication 解析得到。
     * 业务含义：在指定项目下为用户新建一个会话。
     * 返回：201 Created 及会话信息（ConversationResponse）。 */
    @PostMapping
    public ResponseEntity<ConversationResponse> create(
            @Valid @RequestBody CreateConversationRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conversationService.create(userId, request));
    }

    /** GET /api/v1/conversations?projectId=：列出某项目下的会话列表。
     * 入参：必填查询参数 projectId（项目 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：返回该用户在该项目下拥有的全部会话。
     * 返回：200 OK 及会话信息列表（List<ConversationResponse>）。 */
    @GetMapping
    public ResponseEntity<List<ConversationResponse>> list(
            @RequestParam Long projectId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(conversationService.list(userId, projectId));
    }

    /** GET /api/v1/conversations/{conversationId}/messages：获取某会话的历史消息列表。
     * 入参：路径变量 conversationId（会话 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：返回该会话下按时间排序的全部消息（含来源引用）。
     * 返回：200 OK 及消息列表（List<MessageResponse>）。 */
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<List<MessageResponse>> messages(
            @PathVariable Long conversationId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(conversationService.messages(userId, conversationId));
    }

    /** POST /api/v1/conversations/{conversationId}/messages（produces=text/event-stream）：基于 RAG 的流式问答接口。
     * 入参：路径变量 conversationId（会话 ID），@Valid ChatRequest（用户提问内容），当前登录用户由 Authentication 解析得到。
     * 业务含义：以 SSE 流式方式将该会话下的用户提问发送给大模型，结合知识库检索结果逐步推送回答。
     * 返回：SseEmitter，用于向客户端持续推送流式消息事件。 */
    @PostMapping(value = "/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @PathVariable Long conversationId,
            @Valid @RequestBody ChatRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return chatService.ask(userId, conversationId, request);
    }
}
