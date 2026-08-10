# Wisread MVP Phase 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add RAG Q&A to the backend: conversation/message storage, vector retrieval, rerank, SSE streaming answers, and citation persistence through `answer_sources`.

**Architecture:** The chat endpoint verifies conversation ownership, rewrites multi-turn queries, embeds the query, retrieves Top 10 chunks from pgvector, reranks to Top 3, injects chunks into the prompt with `[1]..[3]` markers, streams the LLM response via SSE, then validates citation markers and writes `answer_sources`.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring AI Alibaba, Spring MVC `SseEmitter`, PostgreSQL + pgvector.

---

## Task 1: Conversation Domain

**Files:**
- Create: `backend/src/main/java/com/wisread/entity/Conversation.java`
- Create: `backend/src/main/java/com/wisread/entity/Message.java`
- Create: `backend/src/main/java/com/wisread/entity/AnswerSource.java`
- Create: `backend/src/main/java/com/wisread/repository/ConversationRepository.java`
- Create: `backend/src/main/java/com/wisread/repository/MessageRepository.java`
- Create: `backend/src/main/java/com/wisread/repository/AnswerSourceRepository.java`

- [ ] **Step 1: Map V1 tables to JPA entities**

Fields match `conversations`, `messages`, `answer_sources`.

- [ ] **Step 2: Add repository queries**

- `ConversationRepository.findByUserIdAndId`
- `ConversationRepository.findByUserIdAndDocumentIdOrderByUpdatedAtDesc`
- `MessageRepository.findByConversationIdOrderByCreatedAtAsc`
- `AnswerSourceRepository.findByMessageIdOrderById`

## Task 2: Retrieval and Rerank

**Files:**
- Create: `backend/src/main/java/com/wisread/model/ChunkSearchResult.java`
- Modify: `backend/src/main/java/com/wisread/service/VectorIndexingService.java`
- Create: `backend/src/main/java/com/wisread/service/RerankService.java`

- [ ] **Step 1: Return chunk content with search results**

`search` returns `ChunkSearchResult(id, content, pageStart, pageEnd, distance)`.

- [ ] **Step 2: Add local rerank**

Take Top 3 by distance; interface kept so DashScope rerank can replace it later.

## Task 3: Local Chat Mock

**Files:**
- Create: `backend/src/main/java/com/wisread/service/LocalChatModel.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Implement `ChatModel`**

Return deterministic `[1]`-style answers from the injected chunks when no DashScope key is configured.

- [ ] **Step 2: Add `wisread.chat.mock-enabled`**

Default `true` for local development; set `false` with a real `DASHSCOPE_API_KEY` for production.

## Task 4: Citation Parsing

**Files:**
- Create: `backend/src/main/java/com/wisread/service/CitationParsingService.java`
- Create: `backend/src/main/java/com/wisread/dto/SourceResponse.java`

- [ ] **Step 1: Parse `[1]..[3]` markers**

Extract integer markers from answer text, deduplicate, and drop any marker not present in the retrieved chunks.

- [ ] **Step 2: Build source DTO**

`SourceResponse(index, chunkId, pageStart, pageEnd, snippet)`.

## Task 5: Chat Service and SSE

**Files:**
- Create: `backend/src/main/java/com/wisread/dto/ChatRequest.java`
- Create: `backend/src/main/java/com/wisread/dto/ConversationResponse.java`
- Create: `backend/src/main/java/com/wisread/dto/MessageResponse.java`
- Create: `backend/src/main/java/com/wisread/service/QueryRewriteService.java`
- Create: `backend/src/main/java/com/wisread/service/ChatService.java`
- Create: `backend/src/main/java/com/wisread/controller/ConversationController.java`

- [ ] **Step 1: Implement conversation CRUD**

Create conversation, list by document, list messages. All queries check `user_id`.

- [ ] **Step 2: Implement chat flow**

Save user message -> rewrite query -> embed -> search Top 10 -> rerank Top 3 -> build prompt -> stream LLM via `SseEmitter`.

- [ ] **Step 3: Persist assistant answer and citations**

Accumulate streamed tokens, save assistant `Message`, parse citations, write `answer_sources`, send final SSE event with sources.

## Task 6: Tests

**Files:**
- Create: `backend/src/test/java/com/wisread/service/CitationParsingServiceTest.java`
- Create: `backend/src/test/java/com/wisread/service/ConversationServiceTest.java`

- [ ] **Step 1: Citation parser tests**

`[1]` and `[2]` are kept, `[99]` is dropped.

- [ ] **Step 2: Conversation service tests**

Create conversation requires owned document; message history is ordered.

## Task 7: Verification

Run:
```bash
cd backend
mvn test
mvn spring-boot:run
```

Expected: all tests pass; create conversation, send question, receive SSE answer with `[1]` source, and `answer_sources` rows exist in PostgreSQL.
