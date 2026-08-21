package com.wisread.service;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class QueryRewriteService {

    private final ChatModel chatModel;
    private final TokenCounter tokenCounter;
    private final UsageLogService usageLogService;
    private final String chatModelName;

    public QueryRewriteService(
            ChatModel chatModel,
            TokenCounter tokenCounter,
            UsageLogService usageLogService,
            @Value("${spring.ai.openai.chat.options.model:qwen3.7-plus}") String chatModelName
    ) {
        this.chatModel = chatModel;
        this.tokenCounter = tokenCounter;
        this.usageLogService = usageLogService;
        this.chatModelName = chatModelName;
    }

    public String rewrite(String question, List<com.wisread.entity.Message> history, Long userId) {
        if (history.isEmpty()) {
            return question;
        }
        String historyText = history.stream()
                .map(message -> message.getRole() + ": " + message.getContent())
                .collect(Collectors.joining("\n"));
        SystemMessage system = new SystemMessage(
                "根据历史对话，把用户当前问题改写为独立完整的问题，只输出改写后的问题。"
        );
        String inputText = "历史对话：\n" + historyText + "\n\n当前问题：" + question;
        UserMessage user = new UserMessage(inputText);
        String rewritten = chatModel.call(new Prompt(List.of(system, user)))
                .getResult()
                .getOutput()
                .getText();
        usageLogService.log(userId, chatModelName, tokenCounter.count(inputText), tokenCounter.count(rewritten));
        return rewritten == null || rewritten.isBlank() ? question : rewritten.trim();
    }
}
