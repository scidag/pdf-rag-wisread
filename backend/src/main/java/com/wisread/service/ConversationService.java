package com.wisread.service;

import com.wisread.dto.ConversationResponse;
import com.wisread.dto.CreateConversationRequest;
import com.wisread.dto.MessageResponse;
import com.wisread.dto.SourceResponse;
import com.wisread.entity.AnswerSource;
import com.wisread.entity.Conversation;
import com.wisread.entity.Document;
import com.wisread.entity.DocumentChunk;
import com.wisread.entity.Message;
import com.wisread.exception.ApiException;
import com.wisread.repository.AnswerSourceRepository;
import com.wisread.repository.ConversationRepository;
import com.wisread.repository.DocumentChunkRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.MessageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final DocumentRepository documentRepository;
    private final MessageRepository messageRepository;
    private final AnswerSourceRepository answerSourceRepository;
    private final DocumentChunkRepository documentChunkRepository;

    public ConversationService(
            ConversationRepository conversationRepository,
            DocumentRepository documentRepository,
            MessageRepository messageRepository,
            AnswerSourceRepository answerSourceRepository,
            DocumentChunkRepository documentChunkRepository
    ) {
        this.conversationRepository = conversationRepository;
        this.documentRepository = documentRepository;
        this.messageRepository = messageRepository;
        this.answerSourceRepository = answerSourceRepository;
        this.documentChunkRepository = documentChunkRepository;
    }

    @Transactional
    public ConversationResponse create(Long userId, CreateConversationRequest request) {
        Document document = documentRepository.findByUserIdAndId(userId, request.documentId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "document not found"));
        if (!"READY".equals(document.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "document is not ready");
        }

        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setDocumentId(request.documentId());
        conversation.setTitle(request.title() == null || request.title().isBlank()
                ? document.getFilename()
                : request.title());
        conversationRepository.save(conversation);
        return toResponse(conversation);
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> list(Long userId, Long documentId) {
        return conversationRepository.findByUserIdAndDocumentIdOrderByUpdatedAtDesc(userId, documentId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> messages(Long userId, Long conversationId) {
        Conversation conversation = findOwnedConversation(userId, conversationId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public Conversation findOwnedConversation(Long userId, Long conversationId) {
        return conversationRepository.findByUserIdAndId(userId, conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "conversation not found"));
    }

    private MessageResponse toResponse(Message message) {
        List<SourceResponse> sources = new ArrayList<>();
        if ("assistant".equals(message.getRole())) {
            List<AnswerSource> answerSources = answerSourceRepository.findByMessageIdOrderById(message.getId());
            for (int i = 0; i < answerSources.size(); i++) {
                AnswerSource answerSource = answerSources.get(i);
                DocumentChunk chunk = documentChunkRepository.findById(answerSource.getChunkId()).orElse(null);
                if (chunk == null) {
                    continue;
                }
                sources.add(new SourceResponse(
                        i + 1,
                        chunk.getId(),
                        chunk.getPageStart(),
                        chunk.getPageEnd(),
                        truncate(chunk.getContent())
                ));
            }
        }
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                sources,
                message.getCreatedAt()
        );
    }

    private String truncate(String content) {
        if (content.length() <= 120) {
            return content;
        }
        return content.substring(0, 120) + "...";
    }

    private ConversationResponse toResponse(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getDocumentId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }
}
