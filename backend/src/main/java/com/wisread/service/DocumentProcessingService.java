package com.wisread.service;

import com.wisread.entity.Document;
import com.wisread.entity.DocumentJob;
import com.wisread.exception.ApiException;
import com.wisread.model.PageText;
import com.wisread.model.TextChunk;
import com.wisread.repository.DocumentJobRepository;
import com.wisread.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentProcessingService {

    private static final int MAX_TOKEN_COUNT = 500_000;

    private final DocumentRepository documentRepository;
    private final DocumentJobRepository documentJobRepository;
    private final PdfParsingService pdfParsingService;
    private final ChunkingService chunkingService;
    private final TokenCounter tokenCounter;
    private final EmbeddingService embeddingService;
    private final VectorIndexingService vectorIndexingService;
    private final MinioStorageService minioStorageService;
    private final String embeddingModelVersion;

    public DocumentProcessingService(
            DocumentRepository documentRepository,
            DocumentJobRepository documentJobRepository,
            PdfParsingService pdfParsingService,
            ChunkingService chunkingService,
            TokenCounter tokenCounter,
            EmbeddingService embeddingService,
            VectorIndexingService vectorIndexingService,
            MinioStorageService minioStorageService,
            @Value("${spring.ai.openai.embedding.options.model:qwen3.7-text-embedding}") String embeddingModelVersion
    ) {
        this.documentRepository = documentRepository;
        this.documentJobRepository = documentJobRepository;
        this.pdfParsingService = pdfParsingService;
        this.chunkingService = chunkingService;
        this.tokenCounter = tokenCounter;
        this.embeddingService = embeddingService;
        this.vectorIndexingService = vectorIndexingService;
        this.minioStorageService = minioStorageService;
        this.embeddingModelVersion = embeddingModelVersion;
    }

    @Async("documentTaskExecutor")
    public void processDocument(Long documentId, Long userId) {
        processInternal(documentId, userId);
    }

    private void processInternal(Long documentId, Long userId) {
        Document document = documentRepository.findByUserIdAndId(userId, documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "document not found"));
        DocumentJob job = documentJobRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "document job not found"));

        document.setStatus("PROCESSING");
        job.setStatus("RUNNING");
        job.setStartedAt(Instant.now());
        documentRepository.updateById(document);
        documentJobRepository.updateById(job);

        try {
            byte[] pdfBytes = minioStorageService.getObject(document.getFileKey());
            List<PageText> pages = pdfParsingService.extractPages(pdfBytes);
            if (pages.stream().allMatch(page -> page.text().isBlank())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "scanned or empty PDF is not supported");
            }

            List<TextChunk> chunks = new ArrayList<>();
            int nextChunkIndex = 0;
            int totalTokens = 0;
            for (PageText page : pages) {
                List<TextChunk> pageChunks = chunkingService.splitPage(page, nextChunkIndex);
                chunks.addAll(pageChunks);
                nextChunkIndex += pageChunks.size();
                totalTokens += pageChunks.stream().mapToInt(TextChunk::tokenCount).sum();
            }
            if (totalTokens > MAX_TOKEN_COUNT) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "document exceeds 500000 token limit");
            }

            List<String> texts = chunks.stream().map(TextChunk::content).toList();
            List<float[]> embeddings = embeddingService.embed(texts, userId);
            vectorIndexingService.saveChunks(documentId, userId, chunks, embeddings, embeddingModelVersion);

            document.setPageCount(pages.size());
            document.setTokenCount(totalTokens);
            document.setEmbeddingModelVersion(embeddingModelVersion);
            document.setStatus("READY");
            document.setErrorMessage(null);
            job.setStatus("SUCCEEDED");
            job.setFinishedAt(Instant.now());
            job.setErrorMessage(null);
            documentRepository.updateById(document);
            documentJobRepository.updateById(job);
        } catch (Exception exception) {
            handleFailure(document, job, exception);
        }
    }

    @Transactional
    public void handleFailure(Document document, DocumentJob job, Exception exception) {
        if (document.getRetryCount() < 1) {
            vectorIndexingService.deleteByDocumentId(document.getId());
            document.setRetryCount(document.getRetryCount() + 1);
            document.setStatus("UPLOADED");
            document.setErrorMessage(exception.getMessage());
            job.setAttempt(job.getAttempt() + 1);
            job.setStatus("PENDING");
            job.setStartedAt(null);
            job.setFinishedAt(null);
            job.setErrorMessage(exception.getMessage());
            documentRepository.updateById(document);
            documentJobRepository.updateById(job);
            processInternal(document.getId(), document.getUserId());
        } else {
            document.setStatus("FAILED");
            document.setErrorMessage(exception.getMessage());
            job.setStatus("FAILED");
            job.setFinishedAt(Instant.now());
            job.setErrorMessage(exception.getMessage());
            documentRepository.updateById(document);
            documentJobRepository.updateById(job);
        }
    }
}
