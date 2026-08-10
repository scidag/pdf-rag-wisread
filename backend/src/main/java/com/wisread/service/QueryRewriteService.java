package com.wisread.service;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class QueryRewriteService {

    private final ChatModel chatModel;

    public QueryRewriteService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String rewrite(String question, List<com.wisread.entity.Message> history) {
        if (history.isEmpty()) {
            return question;
        }
        String historyText = history.stream()
                .map(message -> message.getRole() + ": " + message.getContent())
                .collect(Collectors.joining("\n"));
        SystemMessage system = new SystemMessage(
                "根据历史对话，把用户当前问题改写为独立完整的问题，只输出改写后的问题。"
        );
        UserMessage user = new UserMessage(
                "历史对话：\n" + historyText + "\n\n当前问题：" + question
        );
        String rewritten = chatModel.call(new Prompt(List.of(system, user)))
                .getResult()
                .getOutput()
                .getText();
        return rewritten == null || rewritten.isBlank() ? question : rewritten.trim();
    }
}
