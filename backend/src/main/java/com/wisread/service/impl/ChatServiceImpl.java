package com.wisread.service.impl;

import com.wisread.service.ChatService;
import com.wisread.service.ChatLogService;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * RAG 问答核心编排实现（ChatServiceImpl）。
 *
 * <p>端到端数据流（一次 {@code ask} 的流程）：
 * <ol>
 *   <li>权限校验：确认会话归属当前用户，且会话已绑定到某项目（检索范围由项目限定）。</li>
 *   <li>记录用户消息：先把本轮 user 消息落库（status=COMPLETED）。</li>
 *   <li>QueryRewrite：仅在有历史时调用 LLM 把当前问题改写为独立完整问题，便于向量检索。</li>
 *   <li>向量检索：对改写后的 query 做 embedding，在 pgvector 中检索 Top10 候选块（searchWithContent）。</li>
 *   <li>Rerank：对候选做重排（当前实现仅取前 3，见 RerankServiceImpl 说明）。</li>
 *   <li>距离阈值拒答：若最优块的距离 &gt; DISTANCE_THRESHOLD(0.65)，判定“文档无相关信息”，
 *       直接返回固定拒答文案，防止模型幻觉编造。</li>
 *   <li>构建 Prompt：将候选块按编号 [1]..[3] 注入系统提示，要求模型只能用编号引用，禁止编造编号。</li>
 *   <li>流式生成：调用 ChatModel 流式接口，逐 token 通过 SSE 推送给前端。</li>
 *   <li>落库与引用：回答完成后，写入 assistant 消息，并把答案中解析出的引用来源写入 AnswerSource。</li>
 * </ol>
 *
 * <p>关键设计决策：
 * <ul>
 *   <li>检索 Top10 再重排：先广召回以保证不漏，再靠重排/阈值精筛，平衡召回与精度。</li>
 *   <li>DISTANCE_THRESHOLD=0.65：向量距离越大表示越不相似，超过该值视为“无可靠依据”，触发拒答。</li>
 *   <li>引用编号注入：[i] 由后端对检索块统一编号，模型只能引用系统给定的编号，便于前端与 AnswerSource 对齐。</li>
 * </ul>
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);
    // 距离阈值：向量距离（越小越相似）超过该值即认为无可靠依据，触发拒答以防幻觉
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
    private final ChatLogService chatLogService;
    private final ChatModel chatModel;
    private final String chatModelName;
    private final Executor executor;
    private final Semaphore chatSemaphore;
    private final int maxHistoryTokens;
    private final long slowThresholdMs;
    private final int topK;
    private final Timer queueWaitTimer;
    private final Timer ttftTimer;
    private final Timer totalTimer;
    private final Counter rejectionCounter;

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
            ChatLogService chatLogService,
            @Value("${spring.ai.openai.chat.options.model:qwen3.7-plus}") String chatModelName,
            ChatModel chatModel,
            @Value("${wisread.retrieval.top-k:10}") int topK,
            @Value("${wisread.chat.max-concurrent:8}") int maxConcurrent,
            @Value("${wisread.chat.max-history-tokens:2000}") int maxHistoryTokens,
            @Value("${wisread.chat.slow-threshold-ms:10000}") long slowThresholdMs,
            MeterRegistry meterRegistry,
            @Qualifier("chatTaskExecutor") Executor executor
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
        this.chatLogService = chatLogService;
        this.chatModel = chatModel;
        this.chatModelName = chatModelName;
        this.topK = topK;
        this.chatSemaphore = new Semaphore(maxConcurrent);
        this.maxHistoryTokens = maxHistoryTokens;
        this.slowThresholdMs = slowThresholdMs;
        this.executor = executor;
        this.queueWaitTimer = meterRegistry.timer("wisread.chat.queue.wait");
        this.ttftTimer = meterRegistry.timer("wisread.chat.ttft");
        this.totalTimer = meterRegistry.timer("wisread.chat.total");
        this.rejectionCounter = meterRegistry.counter("wisread.chat.rejections");
    }

    /**
     * 发起一次问答。
     *
     * <p>做什么：先同步校验会话归属，立即返回一个 120s 超时的 SSE 发射器，
     * 真正的检索+生成流程提交到异步线程池（documentTaskExecutor）执行，避免阻塞 HTTP 线程。
     */
    public SseEmitter ask(Long userId, Long conversationId, ChatRequest request) {
        Conversation conversation = requireOwnedConversation(userId, conversationId);
        SseEmitter emitter = new SseEmitter(120_000L);
        long submittedAtNanos = System.nanoTime();
        try {
            // 异步执行，逐 token 向 emitter 推送
            executor.execute(() -> processAsk(conversation, request, emitter, submittedAtNanos));
        } catch (RejectedExecutionException exception) {
            rejectionCounter.increment();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "chat queue is full, retry later");
        }
        return emitter;
    }

    /**
     * 校验会话归属当前用户且已绑定项目。
     *
     * <p>为什么：会话必须属于某项目，检索向量时才能量级限定在“该项目文档”范围内，
     * 既保证数据隔离，也决定 RAG 的知识边界。
     */
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

    /**
     * 问答主流程（异步线程内执行）。
     *
     * <p>这是 RAG 编排的核心，依次完成：取历史→落库 user 消息→QueryRewrite 改写→
     * 向量检索 Top10→Rerank→距离阈值拒答→构建 Prompt→流式生成→完成落库。
     */
    private void processAsk(Conversation conversation, ChatRequest request, SseEmitter emitter, long submittedAtNanos) {
        try {
            Long projectId = conversation.getProjectId();

            long queuedMs = (System.nanoTime() - submittedAtNanos) / 1_000_000;
            queueWaitTimer.record(queuedMs, TimeUnit.MILLISECONDS);
            log.info("Chat queuedMs={} conversationId={}", queuedMs, conversation.getId());

            // 取最近 10 条历史（倒序取出后反转，恢复时间正序），用于多轮上下文与改写
            List<com.wisread.entity.Message> history = messageRepository
                    .findTop10ByConversationIdOrderByCreatedAtDesc(conversation.getId())
                    .reversed();
            history = trimHistory(history, maxHistoryTokens);

            // 先把本轮用户提问落库，保证对话持久化
            com.wisread.entity.Message userMessage = new com.wisread.entity.Message();
            userMessage.setConversationId(conversation.getId());
            userMessage.setRole("user");
            userMessage.setContent(request.content());
            userMessage.setStatus("COMPLETED");
            messageRepository.insert(userMessage);

            // 仅在有历史时改写：把指代性提问（如“它”“这个”）补全为独立完整问题，提升检索命中
            String query = queryRewriteService.rewrite(request.content(), history, conversation.getUserId());
            // 对 query 做 embedding，作为向量检索的输入
            float[] queryEmbedding = embeddingService.embed(List.of(query), conversation.getUserId()).get(0);
            // 向量召回 Top10 候选块：先广召回，再靠重排/阈值精筛，平衡召回率与精度
            List<ChunkSearchResult> candidates = vectorIndexingService.searchWithContent(
                    conversation.getUserId(),
                    projectId,
                    queryEmbedding,
                    topK
            );
            // 重排（当前实现仅截前 3，见 RerankServiceImpl）
            List<ChunkSearchResult> chunks = rerankService.rerank(query, candidates);
            // 记录本次提问：问题、模型、检索内容与来源文档
            chatLogService.log(conversation.getUserId(), request.content(), chatModelName, chunks);
            log.info("Chat query='{}' candidates={}", query,
                    chunks.stream().map(ChunkSearchResult::distance).toList());

            // 距离阈值拒答：最优候选距离过大（>0.65）说明文档中没有可靠依据，
            // 直接返回固定文案，防止大模型在无依据时幻觉编造
            if (chunks.isEmpty() || chunks.get(0).distance() > DISTANCE_THRESHOLD) {
                sendNoAnswer(conversation.getId(), emitter);
                return;
            }

            // 用检索块 + 历史构建带编号引用的 Prompt
            Prompt prompt = buildPrompt(query, chunks, history);
            int promptTokens = countPromptTokens(prompt);
            StringBuilder answer = new StringBuilder();
            try {
                chatSemaphore.acquire();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                completeEmitterWithError(emitter, exception);
                return;
            }
            long flowStartNanos = System.nanoTime();
            AtomicBoolean released = new AtomicBoolean(false);
            Runnable releasePermit = () -> {
                if (released.compareAndSet(false, true)) {
                    chatSemaphore.release();
                }
            };
            try {
                // 流式调用大模型，逐段推送 token
                chatModel.stream(prompt).subscribe(
                        response -> {
                            if (response.getResults().isEmpty()) {
                                return;
                            }
                            String token = response.getResult().getOutput().getText();
                            if (token != null && !token.isBlank()) {
                                if (answer.isEmpty()) {
                                    ttftTimer.record(System.nanoTime() - flowStartNanos, TimeUnit.NANOSECONDS);
                                }
                                answer.append(token);
                                try {
                                    // 以 delta 事件把增量文本推给前端
                                    emitter.send(SseEmitter.event()
                                            .name("delta")
                                            .data(Map.of("content", token)));
                                } catch (Exception exception) {
                                    releasePermit.run();
                                    completeEmitterWithError(emitter, exception);
                                }
                            }
                        },
                        error -> {
                            releasePermit.run();
                            completeEmitterWithError(emitter, error);
                        },
                        // 流结束后落库并回传完整答案与引用来源
                        () -> {
                            releasePermit.run();
                            long totalMs = (System.nanoTime() - flowStartNanos) / 1_000_000;
                            totalTimer.record(totalMs, TimeUnit.MILLISECONDS);
                            if (totalMs > slowThresholdMs) {
                                log.warn("Chat slow totalMs={} conversationId={}", totalMs, conversation.getId());
                            }
                            completeAnswer(conversation, chunks, answer.toString(), emitter, promptTokens);
                        }
                );
            } catch (RuntimeException exception) {
                releasePermit.run();
                throw exception;
            }
        } catch (Exception exception) {
            completeEmitterWithError(emitter, exception);
        }
    }

    /**
     * SSE 响应已提交后不能再写 JSON 错误体，改为推送 error 事件再结束连接。
     */
    private void completeEmitterWithError(SseEmitter emitter, Throwable exception) {
        log.warn("Chat SSE error", exception);
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", translateErrorMessage(exception))));
            emitter.complete();
        } catch (Exception sendException) {
            emitter.completeWithError(exception);
        }
    }

    /**
     * 把底层模型异常转成可展示给前端的中文提示。额度耗尽会以 402/429 加
     * quota/balance/arrears 等字样出现在异常消息里，这类情况给用户明确提示；
     * 其余错误统一模糊处理，避免把内部异常细节暴露给前端。
     */
    static String translateErrorMessage(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase();
            if ((lower.contains("402") || lower.contains("429"))
                    && (lower.contains("quota") || lower.contains("balance")
                    || lower.contains("credit") || lower.contains("arrear")
                    || lower.contains("额度") || lower.contains("余额"))) {
                return "AI 模型额度已用完，请充值后再试";
            }
        }
        return "问答请求失败，请稍后重试";
    }

    /**
     * 按 token 预算保留最近的历史消息，控制 prompt 长度与首字延迟。
     */
    private List<com.wisread.entity.Message> trimHistory(
            List<com.wisread.entity.Message> history,
            int budgetTokens
    ) {
        List<com.wisread.entity.Message> kept = new ArrayList<>();
        int total = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            com.wisread.entity.Message message = history.get(i);
            int tokens = tokenCounter.count(message.getContent());
            if (total + tokens > budgetTokens) {
                break;
            }
            total += tokens;
            kept.add(0, message);
        }
        return kept;
    }

    /**
     * 流式生成完成后的收尾：用量统计、落库助手消息、写入引用来源、推送 done 事件。
     */
    private void completeAnswer(
            Conversation conversation,
            List<ChunkSearchResult> chunks,
            String answer,
            SseEmitter emitter,
            int promptTokens
    ) {
        try {
            // 记录本次问答的 token 消耗（用于计费/审计）
            usageLogService.log(
                    conversation.getUserId(),
                    chatModelName,
                    promptTokens,
                    tokenCounter.count(answer)
            );
            // 持久化助手回复
            com.wisread.entity.Message assistantMessage = persistAssistantMessage(
                    conversation.getId(),
                    answer
            );
            // 从答案中解析并校验引用编号，得到与检索块对应的来源列表
            List<SourceResponse> sources = citationParsingService.parseAndValidate(answer, chunks);
            // 将每个引用来源写入 AnswerSource，建立 消息↔块 的关联，供前端展示脚注
            for (SourceResponse source : sources) {
                AnswerSource answerSource = new AnswerSource();
                answerSource.setMessageId(assistantMessage.getId());
                answerSource.setChunkId(source.chunkId());
                answerSource.setDocumentId(source.documentId());
                answerSource.setRelevanceScore(1.0f);
                answerSourceRepository.insert(answerSource);
            }
            // 更新会话活跃时间
            conversation.setUpdatedAt(Instant.now());
            conversationRepository.updateById(conversation);

            // 以 done 事件回传完整答案与来源，并结束 SSE 连接
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("content", answer, "sources", sources)));
            emitter.complete();
        } catch (Exception exception) {
            completeEmitterWithError(emitter, exception);
        }
    }

    /**
     * 统计整段 Prompt 的 token 数（供用量统计）。
     */
    private int countPromptTokens(Prompt prompt) {
        return prompt.getInstructions().stream()
                .mapToInt(message -> tokenCounter.count(message.getText()))
                .sum();
    }

    /**
     * 无可靠依据时的拒答处理：返回固定文案、落库、推送 done 事件。
     */
    private void sendNoAnswer(Long conversationId, SseEmitter emitter) {
        try {
            String answer = "文档中没有找到相关信息";
            persistAssistantMessage(conversationId, answer);
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("content", answer, "sources", List.of())));
            emitter.complete();
        } catch (Exception exception) {
            completeEmitterWithError(emitter, exception);
        }
    }

    /**
     * 写入一条 assistant 消息（status=COMPLETED）。
     */
    private com.wisread.entity.Message persistAssistantMessage(Long conversationId, String content) {
        com.wisread.entity.Message assistantMessage = new com.wisread.entity.Message();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(content);
        assistantMessage.setStatus("COMPLETED");
        messageRepository.insert(assistantMessage);
        return assistantMessage;
    }

    /**
     * 构建带编号引用的 Prompt。
     *
     * <p>做什么：
     * <ol>
     *   <li>系统提示明确“仅依据给定文档作答、无依据则固定拒答、引用必须使用 [1]..[3] 编号且禁止编造”。</li>
     *   <li>按块顺序注入 [i] 编号 + 文件名 + 页码 + 正文，编号由后端统一分配。</li>
     *   <li>追加历史对话（user/assistant）与当前改写后的问题，形成多轮上下文。</li>
     * </ol>
     * 为什么：编号由后端注入而非模型自定，使答案中的 [i] 能稳定映射到 AnswerSource，
     * 保证引用可点击、可追溯，杜绝模型臆造不存在的引用。
     */
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
        // 后端统一为检索块编号，模型只能引用这些编号
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
        // 重建多轮上下文，按角色映射为 User/Assistant 消息
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
