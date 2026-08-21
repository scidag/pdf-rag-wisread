package com.wisread.service;

import com.wisread.model.ChunkSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorIndexingServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private VectorIndexingService vectorIndexingService;

    @BeforeEach
    void setUp() {
        vectorIndexingService = new VectorIndexingService(jdbcTemplate);
    }

    @Test
    void searchWithContentIsScopedToReadyProjectDocuments() {
        when(jdbcTemplate.query(
                anyString(),
                ArgumentMatchers.<RowMapper<ChunkSearchResult>>any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(List.of());

        vectorIndexingService.searchWithContent(1L, 7L, new float[]{0.1f}, 5);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sqlCaptor.capture(),
                ArgumentMatchers.<RowMapper<ChunkSearchResult>>any(),
                any(),
                eq(1L),
                eq(7L),
                eq(5)
        );

        assertThat(sqlCaptor.getValue())
                .contains("JOIN documents d")
                .contains("d.project_id = ?")
                .contains("d.status = 'READY'")
                .contains("dc.user_id = ?");
    }
}
