package com.wisread.service;

import com.wisread.dto.ConversationResponse;
import com.wisread.dto.CreateConversationRequest;
import com.wisread.dto.MessageResponse;
import com.wisread.entity.Conversation;

import java.util.List;

public interface ConversationService {

    ConversationResponse create(Long userId, CreateConversationRequest request);

    List<ConversationResponse> list(Long userId, Long projectId);

    List<MessageResponse> messages(Long userId, Long conversationId);

    Conversation findOwnedConversation(Long userId, Long conversationId);
}
