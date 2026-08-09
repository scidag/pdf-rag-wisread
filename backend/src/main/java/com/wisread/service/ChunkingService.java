package com.wisread.service;

import com.wisread.model.PageText;
import com.wisread.model.TextChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChunkingService {

    private static final int MIN_CHUNK_TOKENS = 800;
    private static final int MAX_CHUNK_TOKENS = 1200;
    private static final int OVERLAP_TOKENS = 150;

    private final TokenCounter tokenCounter;

    public ChunkingService(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    public List<TextChunk> splitPage(PageText page, int nextChunkIndex) {
        String text = normalize(page.text());
        if (text.isBlank()) {
            return List.of();
        }

        List<String> sentences = splitSentences(text);
        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int chunkIndex = nextChunkIndex;

        for (String sentence : sentences) {
            if (current.length() > 0
                    && tokenCounter.count(current.toString()) + tokenCounter.count(sentence) > MAX_CHUNK_TOKENS
                    && tokenCounter.count(current.toString()) >= MIN_CHUNK_TOKENS) {
                chunks.add(new TextChunk(current.toString().trim(), page.page(), page.page(), tokenCounter.count(current.toString())));
                current = new StringBuilder(takeTail(current.toString(), OVERLAP_TOKENS));
                chunkIndex++;
            }
            appendWithSpace(current, sentence);
        }

        if (!current.isEmpty()) {
            chunks.add(new TextChunk(current.toString().trim(), page.page(), page.page(), tokenCounter.count(current.toString())));
        }

        List<TextChunk> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            result.add(chunks.get(i));
        }
        return result;
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private List<String> splitSentences(String text) {
        String[] parts = text.split("(?<=[。！？.!?])");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                sentences.add(part.trim());
            }
        }
        return sentences;
    }

    private String takeTail(String text, int tokens) {
        String[] words = text.split(" ");
        int total = 0;
        StringBuilder tail = new StringBuilder();
        for (int i = words.length - 1; i >= 0; i--) {
            int next = total + tokenCounter.count(words[i]);
            if (next > tokens && tail.length() > 0) {
                break;
            }
            total = next;
            tail.insert(0, words[i] + " ");
        }
        return tail.toString().trim();
    }

    private void appendWithSpace(StringBuilder builder, String value) {
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value);
    }
}
