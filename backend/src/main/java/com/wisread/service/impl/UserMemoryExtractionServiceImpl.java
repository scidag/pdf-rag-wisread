package com.wisread.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisread.service.ChatService;
import com.wisread.service.TokenCounter;
import com.wisread.service.UsageLogService;
import com.wisread.service.UserMemoryExtractionService;
import com.wisread.service.UserMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * 长期记忆提取服务实现（尽力而为的旁路任务）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>前置规则过滤（零成本）：内容过短、命中敏感黑名单、无记忆意图词的轮次直接跳过，
 *       拦掉大多数无价值轮次，避免每轮都调 LLM。</li>
 *   <li>LLM 提取：要求输出 JSON 数组（content/category/importance），解析失败回退为不保存。</li>
 *   <li>全程吞异常记 warn：记忆是热上下文之外的旁路能力，任何失败不得影响问答主流程。</li>
 * </ul>
 */
@Service
public class UserMemoryExtractionServiceImpl implements UserMemoryExtractionService {

    private static final Logger log = LoggerFactory.getLogger(UserMemoryExtractionServiceImpl.class);

    // 敏感信息黑名单：命中即不入库（防密码/密钥/证件等被沉淀进记忆）
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(密码|口令|passwd|password|secret|token|api[_-]?key|密钥|身份证|银行卡|credit card)");
    // 意图词：用户消息包含其一才值得调 LLM 提取
    private static final Pattern INTENT = Pattern.compile(
            "(记住|我是|我的|我们公司|我在|我喜欢|我习惯|我偏好|我叫|以后|我职业|我的工作)");
    // 提取提示词：只提取"关于用户"的信息，不提取文档内容本身
    private static final String EXTRACT_SYSTEM_PROMPT = """
            你是记忆提取器。从下面这轮问答中提取值得长期记住的用户信息（身份、技术背景、偏好、目标、约束），
            输出 JSON 数组，每条格式：{"content": "...", "category": "FACT|GOAL|CONTEXT", "importance": 1-5}。
            规则：
            - 只提取关于"用户"的信息，不提取文档内容本身
            - 每条必须独立完整、脱离上下文仍可理解，不超过 50 字
            - 没有可提取内容时输出 []
            只输出 JSON，不要输出任何其他文字。
            """;

    // 内容短于该长度的轮次基本不含可沉淀信息，直接跳过
    private static final int MIN_QUESTION_LENGTH = 30;
    // 送入 LLM 的回答截断上限，控制提取调用的 token 成本
    private static final int MAX_ANSWER_LENGTH = 2000;
    // 单条记忆内容截断上限
    private static final int MAX_MEMORY_CONTENT_LENGTH = 200;

    private final ChatModel chatModel;
    private final TokenCounter tokenCounter;
    private final UsageLogService usageLogService;
    private final UserMemoryService userMemoryService;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final String chatModelName;

    @Value("${wisread.user-memory.extract-enabled:true}")
    private boolean extractEnabled;

    public UserMemoryExtractionServiceImpl(
            ChatModel chatModel,
            TokenCounter tokenCounter,
            UsageLogService usageLogService,
            UserMemoryService userMemoryService,
            ObjectMapper objectMapper,
            @Qualifier("chatTaskExecutor") Executor executor,
            @Value("${spring.ai.dashscope.chat.options.model:qwen3.7-plus}") String chatModelName
    ) {
        this.chatModel = chatModel;
        this.tokenCounter = tokenCounter;
        this.usageLogService = usageLogService;
        this.userMemoryService = userMemoryService;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.chatModelName = chatModelName;
    }

    /**
     * 异步提取入口：复用 chatTaskExecutor，线程池饱和时直接丢弃本次提取（尽力而为）。
     */
    public void extractAsync(Long userId, Long conversationId, String userMessage, String assistantMessage) {
        if (!extractEnabled) {
            return;
        }
        try {
            executor.execute(() -> extract(userId, conversationId, userMessage, assistantMessage));
        } catch (RejectedExecutionException exception) {
            log.warn("user memory extraction rejected conversationId={}", conversationId);
        }
    }

    /**
     * 同步提取流程（包级可见，便于单测）：过滤 → LLM 提取 → 解析入库。
     */
    void extract(Long userId, Long conversationId, String userMessage, String assistantMessage) {
        try {
            if (!shouldExtract(userMessage)) {
                return;
            }
            List<Map<String, Object>> items = callExtractionModel(userId, userMessage, assistantMessage);
            for (Map<String, Object> item : items) {
                saveItem(userId, conversationId, item);
            }
        } catch (Exception exception) {
            log.warn("user memory extraction failed conversationId={}", conversationId, exception);
        }
    }

    /**
     * 前置规则过滤：过短、敏感、无意图词的轮次不值得提取。
     */
    private boolean shouldExtract(String userMessage) {
        if (userMessage == null || userMessage.length() < MIN_QUESTION_LENGTH) {
            return false;
        }
        if (SENSITIVE.matcher(userMessage).find()) {
            return false;
        }
        return INTENT.matcher(userMessage).find();
    }

    /**
     * 调 LLM 提取记忆，解析 JSON 数组；记录本次提取的 token 用量。
     */
    private List<Map<String, Object>> callExtractionModel(
            Long userId, String userMessage, String assistantMessage
    ) {
        String answer = truncate(assistantMessage == null ? "" : assistantMessage, MAX_ANSWER_LENGTH);
        String inputText = "用户提问：\n" + userMessage + "\n\n助手回答：\n" + answer;
        String raw = chatModel.call(new Prompt(List.of(
                        new SystemMessage(EXTRACT_SYSTEM_PROMPT),
                        new UserMessage(inputText))))
                .getResult()
                .getOutput()
                .getText();
        usageLogService.log(userId, chatModelName,
                tokenCounter.count(EXTRACT_SYSTEM_PROMPT) + tokenCounter.count(inputText),
                tokenCounter.count(raw == null ? "" : raw));
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return parseItems(stripCodeFence(raw.trim()));
    }

    /**
     * 解析 LLM 输出的 JSON 数组，格式异常时返回空列表（不保存任何条目）。
     */
    private List<Map<String, Object>> parseItems(String json) {
        try {
            List<Map<String, Object>> items =
                    objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
            return items == null ? List.of() : items;
        } catch (Exception exception) {
            log.warn("user memory extraction returned non-JSON output");
            return List.of();
        }
    }

    /**
     * 单条记忆入库：字段校验与归一化（category/importance 非法值回退默认）。
     */
    private void saveItem(Long userId, Long conversationId, Map<String, Object> item) {
        Object rawContent = item.get("content");
        if (rawContent == null) {
            return;
        }
        String content = truncate(String.valueOf(rawContent).trim(), MAX_MEMORY_CONTENT_LENGTH);
        if (content.isEmpty() || SENSITIVE.matcher(content).find()) {
            return;
        }
        userMemoryService.save(userId, content, normalizeCategory(item.get("category")),
                normalizeImportance(item.get("importance")), conversationId);
    }

    /**
     * category 合法值：FACT / GOAL / CONTEXT，其余回退 FACT。
     */
    private String normalizeCategory(Object category) {
        String value = category == null ? "" : String.valueOf(category).trim().toUpperCase();
        return switch (value) {
            case "GOAL", "CONTEXT" -> value;
            default -> "FACT";
        };
    }

    /**
     * importance 合法值：1-5，其余回退 3。
     */
    private int normalizeImportance(Object importance) {
        try {
            int value = Integer.parseInt(String.valueOf(importance).trim());
            return Math.min(5, Math.max(1, value));
        } catch (NumberFormatException exception) {
            return 3;
        }
    }

    /**
     * 剥掉 LLM 可能包裹的 ```json 代码围栏。
     */
    private String stripCodeFence(String raw) {
        if (raw.startsWith("```")) {
            int firstNewline = raw.indexOf('\n');
            int lastFence = raw.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return raw.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return raw;
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
