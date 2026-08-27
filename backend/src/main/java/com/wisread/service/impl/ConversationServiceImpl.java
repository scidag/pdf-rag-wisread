package com.wisread.service.impl;

import com.wisread.service.ConversationService;

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

/**
 * 会话服务实现（ConversationServiceImpl）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>所有写操作标注 {@code @Transactional}；只读查询用 {@code readOnly=true} 提升性能。</li>
 *   <li>每个方法先校验“会话/项目归属当前用户”，保证跨租户数据隔离。</li>
 *   <li>读取消息历史时，对助手消息回溯 {@code AnswerSource} 与 {@code DocumentChunk}，
 *       并缓存文档名（documentNameCache）避免同一文档被重复查询。</li>
 * </ul>
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversationRepository;
    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final MessageRepository messageRepository;
    private final AnswerSourceRepository answerSourceRepository;
    private final DocumentChunkRepository documentChunkRepository;

    public ConversationServiceImpl(
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

    /**
     * 创建会话。
     *
     * <p>做什么：校验项目归属后，新建一条会话记录；标题为空时默认“新会话”。
     * 为什么：会话必须挂载在项目下，以便后续 RAG 检索限定在项目的文档范围内。
     */
    @Transactional
    public ConversationResponse create(Long userId, CreateConversationRequest request) {
        Project project = projectRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, request.projectId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));

        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setProjectId(project.getId());
        // 标题缺省值，保证前端列表有可读名称
        conversation.setTitle(request.title() == null || request.title().isBlank()
                ? "新会话"
                : request.title());
        conversationRepository.insert(conversation);
        return toResponse(conversation);
    }

    /**
     * 列出某项目下的全部会话（按最近更新时间倒序）。
     */
    @Transactional(readOnly = true)
    public List<ConversationResponse> list(Long userId, Long projectId) {
        projectRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        return conversationRepository.findByUserIdAndProjectIdOrderByUpdatedAtDesc(userId, projectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 获取会话消息历史（含助手回答的引用来源）。
     *
     * <p>为什么：逐消息组装 {@link MessageResponse}，对助手消息额外回溯引用来源，
     * 使用 documentNameCache 缓存“文档ID→文件名”，避免同一文档被反复查库。
     */
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

    /**
     * 校验会话归属当前用户（及项目）并返回实体。
     *
     * <p>为什么：作为权限闸口被多处复用，确保后续操作的数据隔离。
     */
    public Conversation findOwnedConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByUserIdAndId(userId, conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "conversation not found"));
        if (conversation.getProjectId() != null) {
            projectRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, conversation.getProjectId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        }
        return conversation;
    }

    /**
     * 将消息实体转为响应，并为助手消息拼装引用来源列表。
     */
    private MessageResponse toResponse(Message message, Map<Long, String> documentNameCache) {
        List<SourceResponse> sources = new ArrayList<>();
        // 仅助手消息带有引用来源
        if ("assistant".equals(message.getRole())) {
            List<AnswerSource> answerSources = answerSourceRepository.findByMessageIdOrderById(message.getId());
            for (int i = 0; i < answerSources.size(); i++) {
                AnswerSource answerSource = answerSources.get(i);
                DocumentChunk chunk = documentChunkRepository.findById(answerSource.getChunkId()).orElse(null);
                if (chunk == null) {
                    continue;
                }
                // 优先用 AnswerSource 上记录的文档ID，缺失时回退到块所属文档
                Long docId = answerSource.getDocumentId() != null
                        ? answerSource.getDocumentId()
                        : chunk.getDocumentId();
                // 缓存文档名，避免重复查库
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

    /**
     * 截断引用块正文，避免前端展示过长的原始文本（超过 120 字加省略号）。
     */
    private String truncate(String content) {
        if (content.length() <= 120) {
            return content;
        }
        return content.substring(0, 120) + "...";
    }

    /**
     * 将会话实体转为列表/创建场景的响应 DTO。
     */
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
