package com.wisread.service;

import com.wisread.model.PageText;
import com.wisread.model.TextChunk;

import java.util.List;

public interface ChunkingService {

    List<TextChunk> splitPage(PageText page, int nextChunkIndex);
}
