package com.wisread.service;

import com.wisread.dto.SourceResponse;
import com.wisread.model.ChunkSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationParsingServiceTest {

    private final CitationParsingService citationParsingService = new CitationParsingService();

    @Test
    void keepsValidMarkersAndDropsInvalidOnes() {
        String answer = "答案引用 [1] 和 [2]，但不包含 [99]。";
        List<ChunkSearchResult> chunks = List.of(
                new ChunkSearchResult(10L, 100L, "白皮书.pdf", "第一段内容", 1, 1, 0.1),
                new ChunkSearchResult(11L, 100L, "白皮书.pdf", "第二段内容", 2, 2, 0.2),
                new ChunkSearchResult(12L, 101L, "架构.pdf", "第三段内容", 3, 3, 0.3)
        );

        List<SourceResponse> sources = citationParsingService.parseAndValidate(answer, chunks);

        assertThat(sources).hasSize(2);
        assertThat(sources).extracting(SourceResponse::index).containsExactly(1, 2);
        assertThat(sources).extracting(SourceResponse::chunkId).containsExactly(10L, 11L);
        assertThat(sources).extracting(SourceResponse::filename)
                .containsExactly("白皮书.pdf", "白皮书.pdf");
    }

    @Test
    void returnsEmptyWhenNoMarkers() {
        List<SourceResponse> sources = citationParsingService.parseAndValidate(
                "没有引用的回答",
                List.of(new ChunkSearchResult(1L, 100L, "白皮书.pdf", "内容", 1, 1, 0.1))
        );

        assertThat(sources).isEmpty();
    }
}
