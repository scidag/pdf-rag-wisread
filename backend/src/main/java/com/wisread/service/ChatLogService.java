package com.wisread.service;

import com.wisread.model.ChunkSearchResult;

import java.util.List;

public interface ChatLogService {

    void log(Long userId, String question, String model, List<ChunkSearchResult> chunks);
}
