package com.wisread.service;

import com.wisread.dto.SourceResponse;
import com.wisread.model.ChunkSearchResult;

import java.util.List;

public interface CitationParsingService {

    List<SourceResponse> parseAndValidate(String answer, List<ChunkSearchResult> retrievedChunks);
}
