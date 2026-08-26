package com.wisread.service.impl;

import com.wisread.service.CitationParsingService;

import com.wisread.dto.SourceResponse;
import com.wisread.model.ChunkSearchResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CitationParsingServiceImpl implements CitationParsingService {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");
    private static final int SNIPPET_LENGTH = 120;

    public List<SourceResponse> parseAndValidate(String answer, List<ChunkSearchResult> retrievedChunks) {
        Set<Integer> markers = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            markers.add(Integer.valueOf(matcher.group(1)));
        }

        List<SourceResponse> sources = new ArrayList<>();
        for (int i = 0; i < retrievedChunks.size(); i++) {
            int marker = i + 1;
            if (markers.contains(marker)) {
                ChunkSearchResult chunk = retrievedChunks.get(i);
                sources.add(new SourceResponse(
                        marker,
                        chunk.chunkId(),
                        chunk.documentId(),
                        chunk.filename(),
                        chunk.pageStart(),
                        chunk.pageEnd(),
                        truncate(chunk.content())
                ));
            }
        }
        return sources;
    }

    private String truncate(String content) {
        if (content.length() <= SNIPPET_LENGTH) {
            return content;
        }
        return content.substring(0, SNIPPET_LENGTH) + "...";
    }
}
