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
import com.wisread.entity.KnowledgeBase;
import com.wisread.entity.Message;
import com.wisread.entity.Project;
import com.wisread.exception.ApiException;
import com.wisread.model.KnowledgeChunkSourceView;
import com.wisread.repository.AnswerSourceRepository;
import com.wisread.repository.ConversationRepository;
import com.wisread.repository.DocumentChunkRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.KnowledgeBaseRepository;
import com.wisread.repository.MessageRepository;
import com.wisread.repository.ProjectRepository;
import com.wisread.service.KnowledgeVectorIndexingService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeVectorIndexingService knowledgeVectorIndexingService;

    public ConversationServiceImpl(
            ConversationRepository conversationRepository,
            ProjectRepository projectRepository,
            DocumentRepository documentRepository,
            MessageRepository messageRepository,
            AnswerSourceRepository answerSourceRepository,
            DocumentChunkRepository documentChunkRepository,
            KnowledgeBaseRepository knowledgeBaseRepository,
            KnowledgeVectorIndexingService knowledgeVectorIndexingService
    ) {
        this.conversationRepository = conversationRepository;
        this.projectRepository = projectRepository;
        this.documentRepository = documentRepository;
        this.messageRepository = messageRepository;
        this.answerSourceRepository = answerSourceRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeVectorIndexingService = knowledgeVectorIndexingService;
    }

    /**
     * 创建会话。
     *
     * <p>做什么：校验项目归属后，新建一条会话记录；标题为空时默认“新会话”。
     * 为什么：会话必须挂载在项目下，以便后续 RAG 检索限定在项目的文档范围内。
     * 请求可选携带 knowledgeBaseId 绑定独立知识库：传了就校验归属（越权 403），
     * 不传/传 null 时行为与历史版本完全一致（不查知识库）。
     */
    @Transactional
    public ConversationResponse create(Long userId, CreateConversationRequest request) {
        Project project = projectRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, request.projectId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        if (request.knowledgeBaseId() != null) {
            requireBindableKnowledgeBase(userId, request.knowledgeBaseId());
        }

        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setProjectId(project.getId());
        conversation.setKnowledgeBaseId(request.knowledgeBaseId());
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
     * 引用解析双链路：会话绑定了独立知识库时，AnswerSource.chunkId 指向 knowledge_chunks，
     * 走知识库切片查询（user_id 校验归属）；未绑定时保持原有 document_chunks 解析路径不变。
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> messages(Long userId, Long conversationId) {
        Conversation conversation = findOwnedConversation(userId, conversationId);
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        Map<Long, String> documentNameCache = new HashMap<>();
        boolean knowledgeBaseBound = conversation.getKnowledgeBaseId() != null;
        List<MessageResponse> result = new ArrayList<>();
        for (Message message : messages) {
            result.add(toResponse(message, userId, knowledgeBaseBound, documentNameCache));
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
     * 切换会话绑定的独立知识库。
     *
     * <p>做什么：先做会话归属校验（对齐现有 404 约定）；knowledgeBaseId 非空时校验
     * 知识库归属与软删除状态；随后写回 knowledge_base_id。
     * 为什么用条件 UPDATE 而非 updateById：解绑时需把列显式置 NULL，
     * 而 updateById 默认忽略实体中的 null 字段，无法完成解绑。
     * 为什么用字符串列名而非 Lambda：UpdateWrapper 的字符串列名不依赖
     * TableInfo 的 lambda 缓存，列名与 V9 迁移中的 conversations 表结构一致。
     */
    @Transactional
    public ConversationResponse updateKnowledgeBase(Long userId, Long conversationId, Long knowledgeBaseId) {
        Conversation conversation = findOwnedConversation(userId, conversationId);
        if (knowledgeBaseId != null) {
            requireBindableKnowledgeBase(userId, knowledgeBaseId);
        }
        conversation.setKnowledgeBaseId(knowledgeBaseId);
        Instant now = Instant.now();
        // 回填内存实体的 updated_at，保证返回体时间戳与库中一致（UpdateWrapper 不会回写实体）
        conversation.setUpdatedAt(now);
        conversationRepository.update(null, new UpdateWrapper<Conversation>()
                .eq("id", conversation.getId())
                .set("knowledge_base_id", knowledgeBaseId)
                .set("updated_at", now));
        return toResponse(conversation);
    }

    /**
     * 校验目标知识库可被当前用户绑定。
     *
     * <p>做什么：先按“未删除”查询，命中即通过（ARCHIVED 允许绑定，归档库的
     * 既有会话可继续问答）；未命中时区分“已软删除”（404，提示可恢复）与
     * “不存在/越权”（403，避免向非归属者泄露知识库存在性）。
     */
    private KnowledgeBase requireBindableKnowledgeBase(Long userId, Long knowledgeBaseId) {
        return knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, knowledgeBaseId)
                .orElseGet(() -> {
                    if (knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNotNull(userId, knowledgeBaseId)
                            .isPresent()) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "知识库不存在或已删除");
                    }
                    throw new ApiException(HttpStatus.FORBIDDEN, "无权访问该知识库");
                });
    }

    /**
     * 将消息实体转为响应，并为助手消息拼装引用来源列表。
     * knowledgeBaseBound 为 true 时走知识库切片解析（chunkId 指向 knowledge_chunks），
     * 否则保持原有 document_chunks 解析路径不变。
     */
    private MessageResponse toResponse(
            Message message,
            Long userId,
            boolean knowledgeBaseBound,
            Map<Long, String> documentNameCache
    ) {
        List<SourceResponse> sources = new ArrayList<>();
        // 仅助手消息带有引用来源
        if ("assistant".equals(message.getRole())) {
            List<AnswerSource> answerSources = answerSourceRepository.findByMessageIdOrderById(message.getId());
            if (knowledgeBaseBound) {
                appendKnowledgeBaseSources(sources, answerSources, userId);
            } else {
                appendDocumentSources(sources, answerSources, documentNameCache);
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
     * 未绑库会话的原有引用解析路径：AnswerSource.chunkId 指向 document_chunks，
     * 文档名走 documentNameCache 缓存。逻辑与历史版本完全一致，不做任何行为改动。
     */
    private void appendDocumentSources(
            List<SourceResponse> sources,
            List<AnswerSource> answerSources,
            Map<Long, String> documentNameCache
    ) {
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
                    chunk.getLocationLabel(),
                    truncate(chunk.getContent())
            ));
        }
    }

    /**
     * 绑库会话的引用解析路径：AnswerSource.chunkId 指向 knowledge_chunks，
     * 按 user_id 批量查询知识库切片视图（越权切片不可见，缺失时与原路径一致静默跳过）。
     * 文件名由 JOIN knowledge_documents 直接带出，无需文档名缓存。
     */
    private void appendKnowledgeBaseSources(
            List<SourceResponse> sources,
            List<AnswerSource> answerSources,
            Long userId
    ) {
        if (answerSources.isEmpty()) {
            return;
        }
        List<Long> chunkIds = answerSources.stream().map(AnswerSource::getChunkId).distinct().toList();
        Map<Long, KnowledgeChunkSourceView> viewById = new HashMap<>();
        for (KnowledgeChunkSourceView view : knowledgeVectorIndexingService.findViewsByIds(userId, chunkIds)) {
            viewById.put(view.chunkId(), view);
        }
        for (int i = 0; i < answerSources.size(); i++) {
            AnswerSource answerSource = answerSources.get(i);
            KnowledgeChunkSourceView view = viewById.get(answerSource.getChunkId());
            if (view == null) {
                continue;
            }
            // 优先用 AnswerSource 上记录的文档ID，缺失时回退到切片所属文档
            Long docId = answerSource.getDocumentId() != null
                    ? answerSource.getDocumentId()
                    : view.documentId();
            sources.add(new SourceResponse(
                    i + 1,
                    view.chunkId(),
                    docId,
                    view.filename(),
                    view.pageStart(),
                    view.pageEnd(),
                    view.locationLabel(),
                    truncate(view.content())
            ));
        }
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
                conversation.getKnowledgeBaseId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }
}

