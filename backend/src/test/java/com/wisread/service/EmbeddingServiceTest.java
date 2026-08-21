package com.wisread.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingServiceTest {

    @Test
    void splitsLargeBatchesIntoTwentyTexts() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<?> batch = invocation.getArgument(0);
            List<float[]> result = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                result.add(new float[]{i});
            }
            return result;
        });

        UsageLogService usageLogService = mock(UsageLogService.class);
        EmbeddingService service = new EmbeddingService(model, usageLogService, new TokenCounter(), "test-embedding");
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            texts.add("text-" + i);
        }

        List<float[]> embeddings = service.embed(texts, 1L);

        assertThat(embeddings).hasSize(45);
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(model, times(3)).embed(captor.capture());
        assertThat(captor.getAllValues().get(0)).hasSize(20);
        assertThat(captor.getAllValues().get(1)).hasSize(20);
        assertThat(captor.getAllValues().get(2)).hasSize(5);
        verify(usageLogService, times(3)).log(eq(1L), eq("test-embedding"), anyInt(), eq(0));
    }
}
