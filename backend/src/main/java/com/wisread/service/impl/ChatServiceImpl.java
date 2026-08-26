package com.wisread.service.impl;

import com.wisread.service.ChatService;
import com.wisread.service.CitationParsingService;
import com.wisread.service.EmbeddingService;
import com.wisread.service.QueryRewriteService;
import com.wisread.service.RerankService;
import com.wisread.service.TokenCounter;
import com.wisread.service.UsageLogService;
import com.wisread.service.VectorIndexingService;

import com.wisread.dto.ChatRequest;
import com.wisread.dto.SourceResponse;
import com.wisread.entity.AnswerSource;
import com.wisread.entity.Conversation;
import com.wisread.entity.Document;
import com.wisread.exception.ApiException;
import com.wisread.model.ChunkSearchResult;
import com.wisread.repository.AnswerSourceRepository;
import com.wisread.repository.ConversationRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.MessageRepository;
import com.wisread.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);
    private static final double DISTANCE_THRESHOLD = 0.65;

    private final ConversationRepository conversationRepository;
    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final MessageRepository messageRepository;
    private final AnswerSourceRepository answerSourceRepository;
    private final EmbeddingService embeddingService;
    private final VectorIndexingService vectorIndexingService;
    private final RerankService rerankService;
    private final QueryRewriteService queryRewriteService;
    private final CitationParsingService citationParsingService;
    private final TokenCounter tokenCounter;
    private final UsageLogService usageLogService;
    private final ChatModel chatModel;
    private final String chatModelName;
    private final Executor executor;

    public ChatServiceImpl(
            ConversationRepository conversationRepository,
            ProjectRepository projectRepository,
            DocumentRepository documentRepository,
            MessageRepository messageRepository,
            AnswerSourceRepository answerSourceRepository,
            EmbeddingService embeddingService,
            VectorIndexingService vectorIndexingService,
            RerankService rerankService,
            QueryRewriteService queryRewriteService,
            CitationParsingService citationParsingService,
            TokenCounter tokenCounter,
            UsageLogService usageLogService,
            @Value("${spring.ai.openai.chat.options.model:qwen3.7-plus}") String chatModelName,
            ChatModel chatModel,
            @Qualifier("documentTaskExecutor") Executor executor
    ) {
        this.conversationRepository = conversationRepository;
        this.projectRepository = projectRepository;
        this.documentRepository = documentRepository;
        this.messageRepository = messageRepository;
        this.answerSourceRepository = answerSourceRepository;
        this.embeddingService = embeddingService;
        this.vectorIndexingService = vectorIndexingService;
        this.rerankService = rerankService;
        this.queryRewriteService = queryRewriteService;
        this.citationParsingService = citationParsingService;
        this.tokenCounter = tokenCounter;
        this.usageLogService = usageLogService;
        this.chatModel = chatModel;
        this.chatModelName = chatModelName;
        this.executor = executor;
    }

    public SseEmitter ask(Long userId, Long conversationId, ChatRequest request) {
        Conversation conversation = requireOwnedConversation(userId, conversationId);
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.execute(() -> processAsk(conversation, request, emitter));
        return emitter;
    }

    private Conversation requireOwnedConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByUserIdAndId(userId, conversationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "conversation not found"));
        Long projectId = conversation.getProjectId();
        if (projectId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "conversation has no project");
        }
        projectRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        return conversation;
    }

    private void processAsk(Conversation conversation, ChatRequest request, SseEmitter emitter) {
        try {
            Long projectId = conversation.getProjectId();

            List<com.wisread.entity.Message> history = messageRepository
                    .findTop10ByConversationIdOrderByCreatedAtDesc(conversation.getId())
                    .reversed();

            com.wisread.entity.Message userMessage = new com.wisread.entity.Message();
            userMessage.setConversationId(conversation.getId());
            userMessage.setRole("user");
            userMessage.setContent(request.content());
            userMessage.setStatus("COMPLETED");
            messageRepository.insert(userMessage);

            String query = queryRewriteService.rewrite(request.content(), history, conversation.getUserId());
            float[] queryEmbedding = embeddingService.embed(List.of(query), conversation.getUserId()).get(0);
            List<ChunkSearchResult> candidates = vectorIndexingService.searchWithContent(
                    conversation.getUserId(),
                    projectId,
                    queryEmbedding,
                    10
            );
            List<ChunkSearchResult> chunks = rerankService.rerank(query, candidates);
            log.info("Chat query='{}' candidates={}", query,
                    chunks.stream().map(ChunkSearchResult::distance).toList());

            if (chunks.isEmpty() || chunks.get(0).distance() > DISTANCE_THRESHOLD) {
                sendNoAnswer(conversation.getId(), emitter);
                return;
            }

            Prompt prompt = buildPrompt(query, chunks, history);
            int promptTokens = countPromptTokens(prompt);
            StringBuilder answer = new StringBuilder();
            chatModel.stream(prompt).subscribe(
                    response -> {
                        if (response.getResults().isEmpty()) {
                            return;
                        }
                        String token = response.getResult().getOutput().getText();
                        if (token != null && !token.isBlank()) {
                            answer.append(token);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("delta")
                                        .data(Map.of("content", token)));
                            } catch (Exception exception) {
                                emitter.completeWithError(exception);
                            }
                        }
                    },
                    emitter::completeWithError,
                    () -> completeAnswer(conversation, chunks, answer.toString(), emitter, promptTokens)
            );
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    private void completeAnswer(
            Conversation conversation,
            List<ChunkSearchResult> chunks,
            String answer,
            SseEmitter emitter,
            int promptTokens
    ) {
        try {
            usageLogService.log(
                    conversation.getUserId(),
                    chatModelName,
                    promptTokens,
                    tokenCounter.count(answer)
            );
            com.wisread.entity.Message assistantMessage = persistAssistantMessage(
                    conversation.getId(),
                    answer
            );
            List<SourceResponse> sources = citationParsingService.parseAndValidate(answer, chunks);
            for (SourceResponse source : sources) {
                AnswerSource answerSource = new AnswerSource();
                answerSource.setMessageId(assistantMessage.getId());
                answerSource.setChunkId(source.chunkId());
                answerSource.setDocumentId(source.documentId());
                answerSource.setRelevanceScore(1.0f);
                answerSourceRepository.insert(answerSource);
            }
            conversation.setUpdatedAt(Instant.now());
            conversationRepository.updateById(conversation);

            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("content", answer, "sources", sources)));
            emitter.complete();
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    private int countPromptTokens(Prompt prompt) {
        return prompt.getInstructions().stream()
                .mapToInt(message -> tokenCounter.count(message.getText()))
                .sum();
    }

    private void sendNoAnswer(Long conversationId, SseEmitter emitter) {
        try {
            String answer = "文档中没有找到相关信息";
            persistAssistantMessage(conversationId, answer);
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("content", answer, "sources", List.of())));
            emitter.complete();
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    private com.wisread.entity.Message persistAssistantMessage(Long conversationId, String content) {
        com.wisread.entity.Message assistantMessage = new com.wisread.entity.Message();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(content);
        assistantMessage.setStatus("COMPLETED");
        messageRepository.insert(assistantMessage);
        return assistantMessage;
    }

    private Prompt buildPrompt(
            String question,
            List<ChunkSearchResult> chunks,
            List<com.wisread.entity.Message> history
    ) {
        StringBuilder system = new StringBuilder(
                "你只能根据提供的文档内容回答。\n"
                        + "如果文档没有相关信息，回答“文档中没有找到相关信息”。\n"
                        + "引用必须使用系统提供的编号 [1]..[3]。\n"
                        + "禁止自行生成不存在的编号。\n"
        );
        for (int i = 0; i < chunks.size(); i++) {
            ChunkSearchResult chunk = chunks.get(i);
            system.append("\n[")
                    .append(i + 1)
                    .append("] 《")
                    .append(chunk.filename())
                    .append("》 第 ")
                    .append(chunk.pageStart())
                    .append(" 页\n")
                    .append(chunk.content())
                    .append("\n");
        }

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system.toString()));
        for (com.wisread.entity.Message historyMessage : history) {
            if ("user".equals(historyMessage.getRole())) {
                messages.add(new UserMessage(historyMessage.getContent()));
            } else if ("assistant".equals(historyMessage.getRole())) {
                messages.add(new AssistantMessage(historyMessage.getContent()));
            }
        }
        messages.add(new UserMessage(question));
        return new Prompt(messages);
    }
}
