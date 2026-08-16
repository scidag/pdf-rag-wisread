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
import com.wisread.entity.Project;
import com.wisread.exception.ApiException;
import com.wisread.repository.AnswerSourceRepository;
import com.wisread.repository.ConversationRepository;
import com.wisread.repository.DocumentChunkRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.MessageRepository;
import com.wisread.repository.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final MessageRepository messageRepository;
    private final AnswerSourceRepository answerSourceRepository;
    private final DocumentChunkRepository documentChunkRepository;

    public ConversationService(
            ConversationRepository conversationRepository,
            ProjectRepository projectRepository,
            DocumentRepository documentRepository,
            MessageRepository messageRepository,
            AnswerSourceRepository answerSourceRepository,
            DocumentChunkRepository documentChunkRepository
    ) {
        this.conversationRepository = conversationRepository;
        this.projectRepository = projectRepository;
        this.documentRepository = documentRepository;
        this.messageRepository = messageRepository;
        this.answerSourceRepository = answerSourceRepository;
        this.documentChunkRepository = documentChunkRepository;
    }

    @Transactional
    public ConversationResponse create(Long userId, CreateConversationRequest request) {
        Project project = projectRepository.findByUserIdAndId(userId, request.projectId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));

        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setProjectId(project.getId());
        conversation.setTitle(request.title() == null || request.title().isBlank()
                ? "新会话"
                : request.title());
        conversationRepository.save(conversation);
        return toResponse(conversation);
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> list(Long userId, Long projectId) {
        projectRepository.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        return conversationRepository.findByUserIdAndProjectIdOrderByUpdatedAtDesc(userId, projectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MessageResponse> messages(Long userId, Long conversationId) {
        Conversation conversation = findOwnedConversation(userId, conversationId);
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        Map<Long, String> documentNameCache = new HashMap<>();
        List<MessageResponse> result = new ArrayList<>();
        for (Message message : messages) {
            result.add(toResponse(message, documentNameCache));
        }
        return result;
    }

    public Conversation findOwnedConversation(Long userId, Long conversationId) {
        return conversationRepository.findByUserIdAndId(userId, conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "conversation not found"));
    }

    private MessageResponse toResponse(Message message, Map<Long, String> documentNameCache) {
        List<SourceResponse> sources = new ArrayList<>();
        if ("assistant".equals(message.getRole())) {
            List<AnswerSource> answerSources = answerSourceRepository.findByMessageIdOrderById(message.getId());
            for (int i = 0; i < answerSources.size(); i++) {
                AnswerSource answerSource = answerSources.get(i);
                DocumentChunk chunk = documentChunkRepository.findById(answerSource.getChunkId()).orElse(null);
                if (chunk == null) {
                    continue;
                }
                Long docId = answerSource.getDocumentId() != null
                        ? answerSource.getDocumentId()
                        : chunk.getDocumentId();
                String filename = documentNameCache.computeIfAbsent(docId, id -> {
                    Document doc = documentRepository.findById(id).orElse(null);
                    return doc != null ? doc.getFilename() : "未知文档";
                });
                sources.add(new SourceResponse(
                        i + 1,
                        chunk.getId(),
                        docId,
                        filename,
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
                conversation.getProjectId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }
}
