package com.wisread.service;

import com.wisread.model.PageText;
import com.wisread.model.TextChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkingServiceTest {

    private final ChunkingService chunkingService = new ChunkingService(new TokenCounter());

    @Test
    void longPageProducesMultipleChunksWithPageMetadata() {
        String sentence = "这是一句用于测试文档切分的句子，内容本身没有特殊含义。";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            builder.append(sentence).append(' ');
        }

        List<TextChunk> chunks = chunkingService.splitPage(new PageText(8, builder.toString()), 0);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.content()).isNotBlank();
            assertThat(chunk.pageStart()).isEqualTo(8);
            assertThat(chunk.pageEnd()).isEqualTo(8);
            assertThat(chunk.tokenCount()).isPositive();
        });
    }

    @Test
    void blankPageProducesNoChunks() {
        List<TextChunk> chunks = chunkingService.splitPage(new PageText(1, "   \n  "), 0);

        assertThat(chunks).isEmpty();
    }
}
