package com.wisread.service;

import com.wisread.dto.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ChatService {

    SseEmitter ask(Long userId, Long conversationId, ChatRequest request);
}
