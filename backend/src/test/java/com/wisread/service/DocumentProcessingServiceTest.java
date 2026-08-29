package com.wisread.service;

import com.wisread.entity.Document;
import com.wisread.entity.DocumentJob;
import com.wisread.repository.DocumentJobRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.service.impl.DocumentProcessingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentProcessingServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentJobRepository documentJobRepository;

    @Mock
    private PdfParsingService pdfParsingService;

    @Mock
    private ChunkingService chunkingService;

    @Mock
    private TokenCounter tokenCounter;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private VectorIndexingService vectorIndexingService;

    @Mock
    private MinioStorageService minioStorageService;

    private DocumentProcessingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DocumentProcessingServiceImpl(
                documentRepository,
                documentJobRepository,
                pdfParsingService,
                chunkingService,
                tokenCounter,
                embeddingService,
                vectorIndexingService,
                minioStorageService,
                "test-embedding"
        );
        ReflectionTestUtils.setField(service, "runTimeoutMs", 600_000L);
    }

    @Test
    void recoverStuckJobsResetsTimedOutRunningJobToPending() {
        DocumentJob job = new DocumentJob();
        ReflectionTestUtils.setField(job, "id", 1L);
        job.setDocumentId(10L);
        job.setStatus("RUNNING");
        job.setAttempt(0);
        job.setStartedAt(Instant.now().minusSeconds(700));

        when(documentJobRepository.findPendingOlderThan(any())).thenReturn(List.of());
        when(documentJobRepository.findRunningOlderThan(any())).thenReturn(List.of(job));
        when(documentRepository.findById(10L)).thenReturn(Optional.empty());

        service.recoverStuckJobs();

        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getAttempt()).isEqualTo(1);
        verify(documentJobRepository).updateById(job);
    }

    @Test
    void recoverStuckJobsFailsPendingJobWithMaxAttempts() {
        DocumentJob job = new DocumentJob();
        ReflectionTestUtils.setField(job, "id", 2L);
        job.setDocumentId(10L);
        job.setStatus("PENDING");
        job.setAttempt(2);

        when(documentJobRepository.findPendingOlderThan(any())).thenReturn(List.of(job));
        when(documentJobRepository.findRunningOlderThan(any())).thenReturn(List.of());

        service.recoverStuckJobs();

        assertThat(job.getStatus()).isEqualTo("FAILED");
        verify(documentJobRepository).updateById(job);
    }
}
