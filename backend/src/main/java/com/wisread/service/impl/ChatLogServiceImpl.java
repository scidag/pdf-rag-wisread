package com.wisread.service.impl;

import com.wisread.entity.ChatLog;
import com.wisread.model.ChunkSearchResult;
import com.wisread.repository.ChatLogRepository;
import com.wisread.service.ChatLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatLogServiceImpl implements ChatLogService {

    private static final Logger log = LoggerFactory.getLogger(ChatLogServiceImpl.class);

    private final ChatLogRepository chatLogRepository;

    public ChatLogServiceImpl(ChatLogRepository chatLogRepository) {
        this.chatLogRepository = chatLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long userId, String question, String model, List<ChunkSearchResult> chunks) {
        try {
            ChatLog chatLog = new ChatLog();
            chatLog.setUserId(userId);
            chatLog.setQuestion(question);
            chatLog.setModel(model);
            chatLog.setRetrievedContent(chunks.stream()
                    .map(chunk -> "《" + chunk.filename() + "》 第 " + chunk.pageStart() + " 页\n" + chunk.content())
                    .collect(Collectors.joining("\n\n")));
            chatLog.setDocumentNames(chunks.stream()
                    .map(ChunkSearchResult::filename)
                    .distinct()
                    .collect(Collectors.joining(", ")));
            chatLogRepository.insert(chatLog);
        } catch (RuntimeException exception) {
            log.warn("failed to write chat log", exception);
        }
    }
}
