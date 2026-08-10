package com.wisread.service;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@Primary
@ConditionalOnProperty(name = "wisread.chat.mock-enabled", havingValue = "true", matchIfMissing = true)
public class LocalChatModel implements ChatModel {

    private static final String CHUNK_PREFIX = "[1] ";

    @Override
    public ChatResponse call(Prompt prompt) {
        String system = prompt.getInstructions().stream()
                .filter(message -> message instanceof SystemMessage)
                .map(message -> ((SystemMessage) message).getText())
                .findFirst()
                .orElse("");
        String userText = prompt.getInstructions().stream()
                .filter(message -> message instanceof UserMessage)
                .reduce((first, second) -> second)
                .map(message -> ((UserMessage) message).getText())
                .orElse("");
        String answer = buildMockAnswer(system, userText);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    private String buildMockAnswer(String system, String userText) {
        String snippet = findFirstChunk(system);
        if (snippet == null) {
            int questionIndex = userText.lastIndexOf("当前问题：");
            if (questionIndex >= 0) {
                return userText.substring(questionIndex + "当前问题：".length()).trim();
            }
            return userText.isBlank() ? "文档中没有找到相关信息" : userText;
        }
        if (snippet.length() > 80) {
            snippet = snippet.substring(0, 80) + "...";
        }
        return "文档中提到：" + snippet + " [1]";
    }

    private String findFirstChunk(String system) {
        for (String line : system.split("\n")) {
            if (line.startsWith(CHUNK_PREFIX)) {
                return line.substring(CHUNK_PREFIX.length()).trim();
            }
        }
        return null;
    }
}
