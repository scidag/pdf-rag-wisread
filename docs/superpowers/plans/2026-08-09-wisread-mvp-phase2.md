# Wisread MVP Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add PDF upload, MinIO storage, async parsing, chunking, Embedding, pgvector indexing, document status polling, and deletion to the Spring Boot backend.

**Architecture:** Upload creates `document(UPLOADED)` and `document_job(PENDING)`, then an `@Async` task extracts text with PDFBox, chunks text, embeds with DashScope, writes rows to `document_chunks`, and flips status to `READY`. Failures retry once and clean partial chunks before retry.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring AI Alibaba, PDFBox 3.0.8, PostgreSQL + pgvector, MinIO.

---

## Task 1: Dependencies and Async Configuration

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/java/com/wisread/WisreadApplication.java`
- Create: `backend/src/main/java/com/wisread/config/AsyncConfig.java`

- [ ] **Step 1: Add PDFBox dependency**

```xml
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.8</version>
</dependency>
```

- [ ] **Step 2: Enable async and configure task executor**

Add `@EnableAsync` and a `documentTaskExecutor` bean with core pool 2, max 4, queue 20.

## Task 2: Document Domain

**Files:**
- Create: `backend/src/main/java/com/wisread/entity/Document.java`
- Create: `backend/src/main/java/com/wisread/entity/DocumentChunk.java`
- Create: `backend/src/main/java/com/wisread/entity/DocumentJob.java`
- Create: `backend/src/main/java/com/wisread/repository/DocumentRepository.java`
- Create: `backend/src/main/java/com/wisread/repository/DocumentChunkRepository.java`
- Create: `backend/src/main/java/com/wisread/repository/DocumentJobRepository.java`

- [ ] **Step 1: Map Flyway tables to JPA entities**

Fields match `V1__init_schema.sql`: `documents`, `document_chunks`, `document_jobs`.

- [ ] **Step 2: Add repository queries**

- `DocumentRepository.findByUserIdAndId`
- `DocumentRepository.findByUserIdOrderByCreatedAtDesc`
- `DocumentChunkRepository.deleteByDocumentId`
- `DocumentChunkRepository.countByDocumentId`
- `DocumentJobRepository.findByDocumentId`

## Task 3: PDF Parsing and Chunking

**Files:**
- Create: `backend/src/main/java/com/wisread/service/TokenCounter.java`
- Create: `backend/src/main/java/com/wisread/service/PdfParsingService.java`
- Create: `backend/src/main/java/com/wisread/service/ChunkingService.java`
- Create: `backend/src/main/java/com/wisread/model/PageText.java`
- Create: `backend/src/main/java/com/wisread/model/TextChunk.java`

- [ ] **Step 1: Implement token estimate**

`tokenCount = max(1, text.length() / 4)` as MVP heuristic.

- [ ] **Step 2: Parse PDF page by page**

Use PDFBox `PDFTextStripper` with `setStartPage/setEndPage` to return `PageText(page, text)` for each page.

- [ ] **Step 3: Split each page into chunks**

Split by paragraph/sentence boundaries; target 800-1200 tokens per chunk, overlap 100-200 tokens. Each chunk records `chunkIndex`, `pageStart`, `pageEnd`, `tokenCount`.

## Task 4: Embedding and Vector Storage

**Files:**
- Create: `backend/src/main/java/com/wisread/service/EmbeddingService.java`
- Create: `backend/src/main/java/com/wisread/service/VectorIndexingService.java`

- [ ] **Step 1: Embed chunks in batches**

Use Spring AI `EmbeddingModel.embed(List<String>)`.

- [ ] **Step 2: Write vectors with JdbcTemplate**

Use `org.postgresql.util.PGobject` with type `vector` and value `[x,y,...]`; insert into `document_chunks`.

- [ ] **Step 3: Implement vector search for later use**

`ORDER BY embedding <=> CAST(? AS vector) LIMIT n` filtered by `user_id` and `document_id`.

## Task 5: Upload and Processing API

**Files:**
- Create: `backend/src/main/java/com/wisread/dto/DocumentResponse.java`
- Create: `backend/src/main/java/com/wisread/dto/UploadDocumentResponse.java`
- Create: `backend/src/main/java/com/wisread/service/DocumentService.java`
- Create: `backend/src/main/java/com/wisread/service/DocumentProcessingService.java`
- Create: `backend/src/main/java/com/wisread/controller/DocumentController.java`

- [ ] **Step 1: Implement upload**

Validate PDF magic bytes (`%PDF`), max 20MB, save to MinIO, create document + job, trigger async processing.

- [ ] **Step 2: Implement async processing**

`PENDING -> RUNNING -> parse -> chunk -> embed -> write -> READY`; on failure retry once after deleting partial chunks, then `FAILED`.

- [ ] **Step 3: Implement list, status, delete**

All queries enforce `user_id` ownership; delete removes MinIO object and cascades DB rows.

## Task 6: Tests

**Files:**
- Create: `backend/src/test/java/com/wisread/service/TokenCounterTest.java`
- Create: `backend/src/test/java/com/wisread/service/ChunkingServiceTest.java`
- Create: `backend/src/test/java/com/wisread/service/DocumentServiceTest.java`

- [ ] **Step 1: Token counter tests**

Empty text returns 1; Chinese text returns `length / 4`.

- [ ] **Step 2: Chunking tests**

Long text produces multiple chunks with overlap and page metadata.

- [ ] **Step 3: Document service tests**

Upload rejects invalid PDF and oversize file; delete only removes owned documents.

## Task 7: Verification

Run:
```bash
cd backend
mvn test
mvn spring-boot:run
```

Expected: all tests pass; upload endpoint accepts a PDF, status reaches `READY` within 60 seconds, and `document_chunks` rows exist in PostgreSQL.
