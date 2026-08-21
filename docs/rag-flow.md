# 智阅 RAG 完整流程

> 本文档只讲 RAG：从 PDF 上传到切块、向量化、pgvector 存储，再到查询改写、召回、重排、生成、引用校验的完整链路。全部内容以当前代码实现为准。
>
> 更宽泛的认证、上传状态机、SSE 协议等可参考 [逻辑流程链路.md](逻辑流程链路.md)。

## 1. 整体架构

```
前端 Next.js ──HTTP/SSE──▶ Spring Boot ──JDBC──▶ PostgreSQL 16 + pgvector
                            │  ▲
                            │  │ DashScope（qwen-plus / text-embedding-v3）
                            ▼  │
                          MinIO（PDF 原文件）
```

RAG 分两条链路：

| 链路 | 入口 | 核心类 |
|---|---|---|
| 入库 | `POST /api/v1/documents`（上传 PDF，异步处理） | `DocumentProcessingService` |
| 问答 | `POST /api/v1/conversations/{id}/messages`（SSE 流式） | `ChatService` |

关键常量：

| 常量 | 值 | 位置 |
|---|---:|---|
| 单文件上限 | 100 MB | `DocumentService.MAX_FILE_SIZE` |
| 单项目文档上限 | 5 个 | `DocumentService.MAX_DOCUMENTS_PER_PROJECT` |
| 单个文档 token 上限 | 500,000 | `DocumentProcessingService.MAX_TOKEN_COUNT` |
| 最小切片 token | 800 | `ChunkingService.MIN_CHUNK_TOKENS` |
| 最大切片 token | 1,200 | `ChunkingService.MAX_CHUNK_TOKENS` |
| 切片重叠 token | 150 | `ChunkingService.OVERLAP_TOKENS` |
| 向量召回数 | 10 | `ChatService` 调用处 |
| 重排后进入 Prompt 的块数 | 3 | `RerankService.rerank()` |
| 相似度兜底阈值 | cosine distance > 0.65 则拒答 | `ChatService.DISTANCE_THRESHOLD` |
| 来源片段长度 | 120 字符 | `CitationParsingService.SNIPPET_LENGTH` |

---

## 2. 入库链路：PDF → 切片 → 向量 → pgvector

### 2.1 上传

`DocumentService.upload(userId, projectId, file)`：

1. 校验项目归属、文件非空、大小 ≤ 100 MB、前 4 字节为 `%PDF`、项目内文档数 < 5。
2. 原始 PDF 上传到 MinIO，`fileKey = userId + "/" + UUID + ".pdf"`。
3. 写入 `documents`（状态 `UPLOADED`）和 `document_jobs`（状态 `PENDING`）。
4. 调用 `DocumentProcessingService.processDocument()`，该方法标注 `@Async("documentTaskExecutor")`，接口立即返回。

### 2.2 异步处理编排

`DocumentProcessingService.processInternal(documentId, userId)`：

```
状态推进：documents=PROCESSING，document_jobs=RUNNING
  ├─ ① 从 MinIO 读 PDF bytes
  ├─ ② PdfParsingService.extractPages() → 逐页 List<PageText>
  ├─ ③ 逐页 ChunkingService.splitPage() → 全文档 chunks
  ├─ ④ 累计 token 数，> 500_000 则失败
  ├─ ⑤ EmbeddingService.embed(texts) → List<float[]>
  ├─ ⑥ VectorIndexingService.saveChunks() → 写 document_chunks
  └─ 成功：documents=READY，document_jobs=SUCCEEDED
```

失败时 `handleFailure()` 最多重试一次：先删除已写入的 chunks，状态回退到 `UPLOADED`/`PENDING` 再跑一遍；再次失败则置为 `FAILED`。

### 2.3 PDF 解析

`PdfParsingService.extractPages()` 用 Apache PDFBox：

- 加密 PDF 直接拒绝。
- 逐页用 `PDFTextStripper` 提取文本，得到 `PageText(page, text)`。
- 所有页文本都为空白 → 拒绝（扫描版/空 PDF 不支持）。

### 2.4 切片算法

`ChunkingService.splitPage(page, nextChunkIndex)` 按页独立切片，不跨页合并：

1. **规整**：`replaceAll("\\s+", " ")` 把连续空白压成单个空格并 trim。
2. **分句**：用正则 `(?<=[。！？.!?])` 在句末标点后切分（lookbehind 保留标点）。
3. **贪心累积**：
   - 逐句追加到当前块；
   - 追加前判断：当前块非空、当前块 + 新句子 > 1200 token、当前块 ≥ 800 token 时，先把当前块落盘；
   - 新块以当前块尾部最多 150 token 的文本开头（按空格分词从末尾往前取），实现重叠；
   - 页内句子全部处理完后，剩余文本也落盘。
4. **元数据**：`pageStart = pageEnd = 当前页码`；`tokenCount` 用 `TokenCounter` 估算。

`TokenCounter.count()` 是简单估算：`max(1, text.length() / 4)`，不是真实 tokenizer。

注意：`splitPage()` 的 `nextChunkIndex` 参数目前实际没有被写入 `TextChunk`；入库时的 `chunk_index` 是 `VectorIndexingService` 按全文档 chunks 列表序号 `i` 写入的 0-based 全局序号。

### 2.5 向量化

`EmbeddingService` 只是 Spring AI `EmbeddingModel` 的薄封装，批量调用：

- 默认 `LocalEmbeddingModel`（`wisread.embedding.mock-enabled=true`）：SHA-256 生成确定性向量，1024 维并归一化。能跑通全链路，但没有语义能力。
- 关闭 mock 后使用 DashScope `text-embedding-v3`。

### 2.6 向量入库

`VectorIndexingService.saveChunks()` 逐块 `INSERT` 到 `document_chunks`：

```sql
INSERT INTO document_chunks
  (document_id, user_id, chunk_index, content, page_start, page_end,
   token_count, embedding, embedding_model_version)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
```

`embedding` 通过 `PGobject` 以 `vector` 类型写入，并记录 `embedding_model_version`。

---

## 3. 存储结构

核心表（Flyway 迁移：`V1__init_schema.sql` + `V4__add_projects.sql`）：

| 表 | 作用 | 关键字段 |
|---|---|---|
| `projects` | 项目，文档和会话的归属 | `user_id`, `name` |
| `documents` | PDF 元数据 | `project_id`, `file_key`, `status`, `page_count`, `token_count`, `embedding_model_version` |
| `document_chunks` | 切片 + 向量 | `document_id`, `user_id`, `chunk_index`, `content`, `page_start`, `page_end`, `token_count`, `embedding VECTOR(1024)`, `embedding_model_version` |
| `conversations` | 项目级会话 | `project_id`（`document_id` 保留但已不使用） |
| `messages` | 用户/助手消息 | `role`, `content` |
| `answer_sources` | 答案引用 | `message_id`, `chunk_id`, `document_id`, `relevance_score` |

向量索引：

```sql
CREATE INDEX idx_chunks_embedding
  ON document_chunks USING hnsw (embedding vector_cosine_ops);
```

删除文档时 `documents` 行被物理删除，`document_chunks` 通过 `ON DELETE CASCADE` 自动清掉（MinIO 里的原文件也同步删除）。

---

## 4. 问答链路：改写 → 召回 → 重排 → 生成 → 引用

入口 `ChatService.ask()` 创建 120 秒超时的 `SseEmitter`，把 `processAsk()` 丢到异步线程池，立即返回 SSE 流。

### 4.1 主流程

```
processAsk()
  ├─ ① 校验 conversation/project 归属（按 userId 过滤）
  ├─ ② 保存用户消息
  ├─ ③ 取最近 10 条历史消息（正序）
  ├─ ④ QueryRewriteService.rewrite()：有历史时用 LLM 把当前问题改写为独立问题
  ├─ ⑤ EmbeddingService.embed([query]) → 查询向量
  ├─ ⑥ searchWithContent(userId, projectId, queryEmbedding, 10)
  ├─ ⑦ RerankService.rerank(query, candidates) → 取前 3
  ├─ ⑧ 最相似 distance > 0.65 或为空 → 兜底"文档中没有找到相关信息"
  ├─ ⑨ buildPrompt() → chatModel.stream() → SSE delta 逐 token 输出
  └─ ⑩ completeAnswer()：保存助手消息、解析引用、写 answer_sources、SSE done
```

### 4.2 向量召回

`VectorIndexingService.searchWithContent()` 是**项目级多文档检索**：

```sql
SELECT dc.id, dc.document_id, dc.content, dc.page_start, dc.page_end,
       dc.embedding <=> CAST(? AS vector) AS distance,
       d.filename AS document_filename
FROM document_chunks dc
JOIN documents d ON d.id = dc.document_id
WHERE dc.user_id = ?
  AND d.project_id = ?
  AND d.status = 'READY'
ORDER BY distance
LIMIT ?;
```

`<=>` 是 pgvector 的余弦距离算子；只检索当前用户、当前项目下状态为 `READY` 的文档，天然做了用户隔离。

### 4.3 重排与兜底

`RerankService.rerank()` 当前只是 `candidates.stream().limit(3)`，没有真正的语义重排。

兜底规则：召回为空，或最相似块 `distance > 0.65` 时直接返回“文档中没有找到相关信息”，不调用生成模型。

### 4.4 Prompt 组装

`buildPrompt()` 的 System 消息：

```text
你只能根据提供的文档内容回答。
如果文档没有相关信息，回答"文档中没有找到相关信息"。
引用必须使用系统提供的编号 [1]..[3]。
禁止自行生成不存在的编号。

[1] 《filename》 第 12 页
<chunk content>
[2] 《filename》 第 15 页
<chunk content>
[3] ...
```

后面接历史消息（User/Assistant）和当前问题。

### 4.5 生成与引用

- `chatModel.stream()` 默认走 DashScope `qwen-plus`，关闭 mock 才生效；默认 mock 是 `LocalChatModel`，直接取第一块拼答案并输出 `[1]`。
- 每个非空 token 发送 SSE `event: delta`。
- 完成后 `CitationParsingService.parseAndValidate()` 用正则 `\[(\d+)\]` 提取答案里的编号，只保留落在 `1..retrievedChunks.size()` 范围内的编号；模型编造的超范围编号会被丢弃。
- 每个合法引用写一条 `answer_sources`，片段截取前 120 字符，`relevance_score` 当前固定 `1.0`。
- 最后发送 `event: done`，携带完整答案和来源列表，前端 `SourceCard` 展示文档名、页码和原文片段。

---

## 5. 模型配置

`application.yml` 中：

```yaml
spring.ai.dashscope:
  api-key: ${DASHSCOPE_API_KEY:dummy-local-key}
  chat.options.model: ${WISREAD_CHAT_MODEL:qwen-plus}
  embedding.options.model: ${WISREAD_EMBEDDING_MODEL:text-embedding-v3}

wisread.embedding.mock-enabled: ${WISREAD_EMBEDDING_MOCK_ENABLED:true}
wisread.chat.mock-enabled: ${WISREAD_CHAT_MOCK_ENABLED:true}
```

接入真实模型：

```bash
export DASHSCOPE_API_KEY=your_key
export WISREAD_EMBEDDING_MOCK_ENABLED=false
export WISREAD_CHAT_MOCK_ENABLED=false
```

注意：`document_chunks.embedding` 列固定 `VECTOR(1024)`。更换 embedding 模型（尤其维度不同）时，必须重建索引并保证维度一致，否则入库会失败；`embedding_model_version` 就是用于识别这种不一致。

---

## 6. 代码索引

| 环节 | 文件 |
|---|---|
| 上传校验与 MinIO 落盘 | `backend/src/main/java/com/wisread/service/DocumentService.java` |
| 异步处理编排/失败重试 | `backend/src/main/java/com/wisread/service/DocumentProcessingService.java` |
| PDF 解析 | `backend/src/main/java/com/wisread/service/PdfParsingService.java` |
| 切片 | `backend/src/main/java/com/wisread/service/ChunkingService.java` |
| token 估算 | `backend/src/main/java/com/wisread/service/TokenCounter.java` |
| 向量化 | `backend/src/main/java/com/wisread/service/EmbeddingService.java` |
| 向量入库/检索 | `backend/src/main/java/com/wisread/service/VectorIndexingService.java` |
| 本地 Mock Embedding | `backend/src/main/java/com/wisread/service/LocalEmbeddingModel.java` |
| 问答主流程 | `backend/src/main/java/com/wisread/service/ChatService.java` |
| 查询改写 | `backend/src/main/java/com/wisread/service/QueryRewriteService.java` |
| 重排 | `backend/src/main/java/com/wisread/service/RerankService.java` |
| 引用解析 | `backend/src/main/java/com/wisread/service/CitationParsingService.java` |
| 本地 Mock Chat | `backend/src/main/java/com/wisread/service/LocalChatModel.java` |
| 数据库结构 | `backend/src/main/resources/db/migration/V1__init_schema.sql`、`V4__add_projects.sql` |
