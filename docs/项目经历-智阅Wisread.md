# 智阅 Wisread — 基于 RAG 的 PDF 智能阅读与问答系统

> 本文档可直接作为简历项目经历使用，每条技术要点均标注对应源码文件与行号，便于面试时快速定位并展开讲解。

---

## 项目简介

智阅（Wisread）是一个基于私有知识库的 PDF 智能阅读与问答助手。用户上传 PDF 后，系统自动完成文本解析、分块、向量化索引，并支持基于原文的多轮问答与引用溯源。核心能力包括：项目级知识隔离、RAG 检索增强生成、SSE 流式回答、引用来源可追溯、三层记忆系统（会话记忆 + 长期用户画像）、JWT 双令牌认证。

- **项目角色**：全栈开发（后端 + 前端 + 基础设施）
- **技术栈**：Java 21 / Spring Boot 3.4 / Spring AI / Spring Security / MyBatis-Plus / PostgreSQL + pgvector / MinIO / Redis / Next.js 15 / TypeScript / Tailwind CSS / Docker Compose
- **源码位置**：`E:\AI code\智阅`

---

## 核心技术亮点

### 1. RAG 检索增强生成全链路编排

独立设计并实现了从问题改写 → 向量检索 → 重排 → 阈值拒答 → Prompt 构建 → 流式生成 → 引用解析的完整 RAG 流水线。

- **问答主流程编排**：`backend/src/main/java/com/wisread/service/impl/ChatServiceImpl.java` 第 200-311 行 `processAsk()` 方法，依次完成历史截取、用户消息落库、QueryRewrite 改写、向量检索 Top10、Rerank 截前 3、距离阈值拒答、构建带编号引用的 Prompt、调用 ChatModel 流式生成、完成后落库与引用解析。
- **距离阈值拒答防幻觉**：`ChatServiceImpl.java` 第 80 行定义 `DISTANCE_THRESHOLD = 0.65`，第 242-245 行当最优候选块的余弦距离超过阈值时直接返回"文档中没有找到相关信息"，防止大模型在无依据时幻觉编造。
- **带编号引用的 Prompt 构建**：`ChatServiceImpl.java` 第 447-484 行 `buildPrompt()`，系统提示明确要求模型仅使用 `[1]..[3]` 编号引用且禁止编造编号，后端统一为检索块分配编号，使答案中的引用可稳定映射到 AnswerSource 表。
- **多轮历史 Token 预算控制**：`ChatServiceImpl.java` 第 331-347 行 `trimHistory()`，按 token 预算（默认 2000）从最近消息向前累加保留，控制 prompt 长度与首字延迟。

### 2. 向量检索与 pgvector 集成

基于 PostgreSQL + pgvector 实现向量存储与余弦相似度检索，使用 JdbcTemplate 直接操作向量列，配合 HNSW 索引加速。

- **向量入库**：`backend/src/main/java/com/wisread/service/impl/VectorIndexingServiceImpl.java` 第 40-66 行 `saveChunks()`，逐条 INSERT 文档片段及向量，通过 `toVector()` 方法（第 121-134 行）将 `float[]` 转为 pgvector 的 `[x,y,z]` 字面量，使用 PGobject 绑定避免 SQL 注入。
- **项目级多文档检索**：`VectorIndexingServiceImpl.java` 第 84-114 行 `searchWithContent()`，使用 pgvector 的 `<=>` 余弦距离算子排序，配合 `user_id + project_id + status='READY'` 三重隔离条件，仅召回当前用户、当前项目下已解析完成的文档片段。
- **HNSW 向量索引**：`backend/src/main/resources/db/migration/V1__init_schema.sql` 第 60 行，`CREATE INDEX idx_chunks_embedding ON document_chunks USING hnsw (embedding vector_cosine_ops)`，使用 HNSW 近似最近邻索引加速大规模向量检索。
- **向量维度**：`V1__init_schema.sql` 第 53 行，`embedding VECTOR(1024)`，适配 qwen3.7-text-embedding 模型的 1024 维输出。

### 3. 文档异步处理流水线

设计了基于 `@Async` 的文档处理状态机，支持幂等处理、失败自动重试、卡死任务定时恢复。

- **异步处理入口**：`backend/src/main/java/com/wisread/service/impl/DocumentProcessingServiceImpl.java` 第 94-97 行，`@Async("documentTaskExecutor")` 标注，上传接口不阻塞，真正流水线在专用线程池执行。
- **处理流水线核心**：`DocumentProcessingServiceImpl.java` 第 105-174 行 `processInternal()`，依次完成：原子抢占 PENDING→RUNNING（第 116 行 `claimPending`）、清理旧向量（第 129 行）、MinIO 取 PDF（第 131 行）、逐页抽取文本（第 133 行）、扫描件/空 PDF 检测（第 135-137 行）、逐页分块并累计 token（第 140-148 行）、50 万 token 全局上限校验（第 150-152 行）、批量 embedding（第 156 行）、写入 pgvector（第 157 行）、状态推进到 READY/SUCCEEDED（第 160-169 行）。
- **失败重试机制**：`DocumentProcessingServiceImpl.java` 第 184-211 行 `handleFailure()`，首次失败时清理已写向量、回退状态到 UPLOADED/PENDING、立即重试一次；重试仍失败则标记 FAILED 并保留错误信息。
- **卡死任务定时恢复**：`DocumentProcessingServiceImpl.java` 第 217-245 行 `recoverStuckJobs()`，`@Scheduled` 每 30 秒扫描超过 30 秒未开始的 PENDING 任务和超过超时阈值的 RUNNING 任务，自动恢复处理。
- **专用线程池配置**：`backend/src/main/java/com/wisread/config/AsyncConfig.java` 第 25-39 行 `documentTaskExecutor`（核心 2/最大 4/队列 20），第 44-54 行 `chatTaskExecutor`（核心 4/最大 8/队列 20），文档处理与问答线程池隔离避免互相挤占；第 59-76 行 `withMdc()` 任务装饰器将请求线程的 traceId 传递到异步线程。

### 4. 文档分块策略（带重叠的语义分块）

实现了以句子为最小单元的贪心分块算法，块大小控制在 800~1200 token，相邻块保留 150 token 尾部重叠。

- **分块核心逻辑**：`backend/src/main/java/com/wisread/service/impl/ChunkingServiceImpl.java` 第 41-79 行 `splitPage()`，按中英文句末标点断句（第 88 行正则 `(?<=[。！？.!?])`），贪心累加句子到当前块，当"当前块非空 + 加入下一句超 1200 token + 当前块已达 800 token"三重条件满足时切分。
- **尾部重叠保留上下文**：`ChunkingServiceImpl.java` 第 62 行，切分后新块以上一块末尾 150 token 内容起始，避免句子被硬切断导致语义丢失和检索召回下降；`takeTail()` 方法（第 99-113 行）按空格分词从后往前累加取尾部。
- **Token 轻量估算**：`backend/src/main/java/com/wisread/service/TokenCounter.java` 第 21-27 行，采用"字符数 / 4"经验公式估算，零依赖、开销极低，适用于分块阈值判断与用量统计。

### 5. SSE 流式问答与并发控制

基于 Spring 的 `SseEmitter` 实现逐 token 流式推送，配合 Semaphore 控制并发 LLM 调用数，Micrometer 采集队列等待/首字延迟/总耗时指标。

- **SSE 流式接口**：`backend/src/main/java/com/wisread/controller/ConversationController.java` 第 90-98 行，`POST /conversations/{id}/messages` 以 `text/event-stream` 产出 SseEmitter。
- **异步提交与队列保护**：`ChatServiceImpl.java` 第 162-174 行 `ask()`，同步校验会话归属后立即返回 120s 超时的 SseEmitter，检索+生成提交到 `chatTaskExecutor` 异步执行；线程池拒绝时返回 503 "chat queue is full"。
- **Semaphore 并发限流**：`ChatServiceImpl.java` 第 146 行初始化 `Semaphore(maxConcurrent=8)`，第 252 行在调用 LLM 前 `acquire()`，第 260-264 行通过 `AtomicBoolean` 保证 permit 只释放一次，防止并发 LLM 调用拖垮上游。
- **逐 token 推送**：`ChatServiceImpl.java` 第 267-303 行，订阅 `chatModel.stream(prompt)`，每个非空 token 以 `delta` 事件推送（第 280-282 行），流结束后以 `done` 事件回传完整答案与引用来源（第 388-391 行）。
- **可观测性指标**：`ChatServiceImpl.java` 第 150-153 行注册 `wisread.chat.queue.wait`（队列等待）、`wisread.chat.ttft`（首字延迟）、`wisread.chat.total`（总耗时）Timer 和 `wisread.chat.rejections` Counter；第 298-300 行对超过 10s 的慢查询打 warn 日志。
- **前端 SSE 解析**：`frontend/lib/sse.ts` 第 25-42 行，使用 `ReadableStream` + `TextDecoder` 按 `\n\n` 分隔解析 SSE 事件，第 61-81 行 `handleEvent()` 区分 `delta`（增量文本）和 `done`（完整答案+来源）事件；第 16-18 行实现 401 时自动刷新令牌并重试。

### 6. JWT 双令牌认证与刷新轮换重放检测

实现了 Access Token + Refresh Token 双令牌体系，Refresh Token 采用 SHA-256 哈希存储、轮换机制与重放检测。

- **JWT 生成与解析**：`backend/src/main/java/com/wisread/security/JwtService.java` 第 111-122 行 `buildToken()`，生成带唯一 jti、用户 ID、用户名、角色声明的令牌，使用 HMAC-SHA 签名；第 56-58 行 Access Token（15 分钟），第 64-66 行 Refresh Token（7 天）。
- **密钥安全校验**：`JwtService.java` 第 42-50 行，构造时校验密钥非空且不等于默认开发密钥，否则抛异常阻止启动。
- **Refresh Token 轮换 + 重放检测**：`backend/src/main/java/com/wisread/service/impl/AuthServiceImpl.java` 第 129-171 行 `refresh()`，刷新时将旧哈希归档到 `previous_refresh_token_hash`、写入新哈希（第 160-161 行）；若客户端提交已轮换掉的旧令牌（命中 previous 哈希，第 144-146 行），视为泄露，立即吊销该用户全部会话（第 149 行）并返回 401。
- **Refresh Token 哈希存储**：`AuthServiceImpl.java` 第 207-223 行 `issueTokens()`，仅将 Refresh Token 的 SHA-256 哈希写入 `user_sessions` 表（第 215 行），不存明文；`sha256()` 方法（第 233-240 行）使用 `MessageDigest` + `HexFormat`。
- **登出吊销会话**：`AuthServiceImpl.java` 第 180-187 行 `logout()`，按 Refresh Token 哈希删除对应会话，实现主动登出即令牌失效。
- **Spring Security 无状态配置**：`backend/src/main/java/com/wisread/config/SecurityConfig.java` 第 46-91 行，关闭 CSRF、开启 CORS、`SessionCreationPolicy.STATELESS` 无状态会话；第 54-68 行划分公开接口（auth/register、login、refresh、logout、health）、管理员接口（需 ADMIN 角色）、其余需鉴权；第 85-89 行按顺序注册 TraceIdFilter → AuthRateLimitFilter → JwtAuthenticationFilter。
- **登录接口滑动窗口限流**：`backend/src/main/java/com/wisread/security/AuthRateLimitFilter.java` 第 53-77 行，按客户端 IP 维度使用 `ConcurrentHashMap + ArrayDeque` 实现滑动窗口限流，1 分钟内最多 10 次登录/注册尝试，超限返回 429。

### 7. PDF 解析与 MinIO 对象存储

基于 Apache PDFBox 逐页抽取文本，MinIO 存储原始 PDF 文件，支持加密 PDF/扫描件/损坏文件的异常处理。

- **逐页文本抽取**：`backend/src/main/java/com/wisread/service/impl/PdfParsingServiceImpl.java` 第 32-53 行 `extractPages()`，使用 `Loader.loadPDF()` 加载、`PDFTextStripper` 逐页设置起止页抽取，保证文本与页码一一绑定；第 35-37 行加密 PDF 直接抛 422；第 49-52 行损坏/非法 PDF 抛 422。
- **MinIO 对象存储**：`backend/src/main/java/com/wisread/service/impl/MinioStorageServiceImpl.java` 第 63-77 行 `putObject()` 上传、第 82-93 行 `deleteObject()` 删除、第 98-109 行 `getObject()` 下载；第 36-39 行 `@PostConstruct` 启动时确保存储桶存在。
- **文件上传校验**：`backend/src/main/java/com/wisread/service/impl/DocumentServiceImpl.java` 第 74-136 行 `upload()`，校验项目归属（第 79-80 行）、文件非空（第 83-85 行）、≤100MB（第 87-89 行）、文件头 `%PDF` 校验（第 98-100 行 + 第 197-203 行 `isPdf()`）、单项目最多 5 篇文档（第 102-105 行）；第 108 行用 `userId/uuid.pdf` 生成唯一对象键避免重名冲突。

### 8. 引用来源解析与可追溯

从模型答案中正则提取 `[数字]` 引用标记，校验编号有效性后映射到真实文档片段，建立消息与片段的关联。

- **引用解析与校验**：`backend/src/main/java/com/wisread/service/impl/CitationParsingServiceImpl.java` 第 36-62 行 `parseAndValidate()`，第 26 行正则 `\[(\d+)\]` 提取所有引用编号，使用 `LinkedHashSet` 去重并保留出现顺序（第 37 行）；按"编号 i 对应检索结果第 i-1 个"的规则映射，只输出编号确实落在检索范围内的来源，过滤模型编造的无效编号；第 65-70 行将来源内容截断为 120 字符摘要。
- **引用来源落库**：`ChatServiceImpl.java` 第 373-382 行，回答完成后调用 `citationParsingService.parseAndValidate()` 解析来源，逐条写入 `answer_sources` 表，建立消息↔文档片段的关联，供前端展示可点击的引用脚注。

### 9. 问题改写（Query Rewrite）

多轮对话中结合历史上下文将指代性问题改写为独立完整问题，提升向量检索命中率；无指代时跳过改写减少无效 LLM 调用。

- **改写核心逻辑**：`backend/src/main/java/com/wisread/service/impl/QueryRewriteServiceImpl.java` 第 56-84 行 `rewrite()`，无历史直接返回原问题（第 58-60 行）；无指代且非过短问题时跳过改写（第 62-64 行，`containsPronoun()` 第 86-88 行匹配"它/他/这个/那个/上述"等指代词）；有历史时将历史拼接为 `role: content` 格式（第 66-68 行），调用 LLM 生成独立问题（第 76-79 行）；LLM 返回为空时回退原问题（第 83 行）。

### 10. 批量 Embedding 与用量统计

以 20 条为批次调用 Embedding 模型降低网络往返，每次模型调用均记录输入/输出 token 用量到 `usage_logs` 表。

- **批量向量化**：`backend/src/main/java/com/wisread/service/impl/EmbeddingServiceImpl.java` 第 49-60 行 `embed()`，以 `MAX_BATCH_SIZE=20`（第 25 行）为步长切片，逐批调用 `embeddingModel.embed()`；每批完成后估算输入 token 并落库用量日志（第 56-57 行）。
- **问答用量统计**：`ChatServiceImpl.java` 第 361-366 行，回答完成后记录 prompt token 与答案 token 到 `usage_logs` 表，支持按用户/模型维度的用量审计与计费。

### 11. 前端架构与交互

基于 Next.js 15 App Router + React 19 + TypeScript，实现了登录注册、项目管理、文档上传、会话列表、流式问答、引用来源展示的完整前端。

- **API 封装与 401 自动刷新**：`frontend/lib/api.ts` 第 12-56 行 `apiFetch()`，统一注入 Authorization 头、处理 FormData 与 JSON Content-Type、401 时自动调用 `restoreSession()` 刷新令牌并重试（第 31-45 行）。
- **令牌存储与会话恢复**：`frontend/lib/auth-store.ts` 第 4-5 行内存存储 Access Token（不存 localStorage 防 XSS），第 15-22 行 `restoreSession()` 带并发去重（`refreshPromise` 单例），第 24-39 行 `doRefresh()` 通过 HttpOnly Cookie 携带 Refresh Token 调用 `/auth/refresh`。
- **流式问答组件**：`frontend/components/ChatPanel.tsx` 第 34-45 行，流式回答时将正在生成的内容作为临时消息插入列表实现打字机效果；第 80 行起渲染消息列表与引用来源卡片。
- **项目级工作区**：`frontend/app/projects/[projectId]/page.tsx` 实现项目详情页，左侧项目侧栏（`frontend/components/ProjectSidebar.tsx`）展示文档列表与会话列表，右侧 ChatPanel 进行问答。

### 12. 数据库设计与版本管理

使用 Flyway 管理数据库迁移，共 7 个版本脚本，涵盖用户、会话、文档、向量块、对话、消息、引用来源、项目、聊天日志、长期用户记忆等核心表。

- **初始化 Schema**：`backend/src/main/resources/db/migration/V1__init_schema.sql`，创建 `users`（第 3-12 行）、`user_sessions`（第 14-24 行）、`documents`（第 26-42 行）、`document_chunks`（含 VECTOR(1024) 列和 HNSW 索引，第 44-60 行）、`conversations`（第 62-69 行）、`messages`（第 71-80 行）、`answer_sources`（第 82-90 行）、`document_jobs`（第 92-103 行）、`usage_logs`（第 105-113 行）、`feedback`（第 115-122 行）。
- **项目级隔离迁移**：`V4__add_projects.sql`，新增 `projects` 表（第 2-11 行），`documents` 增加 `project_id`（第 14 行），`conversations` 从文档级改为项目级（第 18-19 行），`answer_sources` 冗余 `document_id` 用于来源卡显示文档名（第 23 行）。
- **软删除迁移**：`V5__add_deleted_at_to_projects.sql`，为 projects 表增加 `deleted_at` 字段实现逻辑删除。
- **聊天日志迁移**：`V6__add_chat_logs.sql`，新增 `chat_logs` 表记录每次问答的问题、模型、检索内容与来源文档。
- **长期记忆迁移**：`V7__add_user_memory.sql`，新增 `user_memory` 表（VECTOR(1024) + status 三态 + HNSW 部分索引），支撑 L2 长期记忆（详见第 13 节）。

### 13. 三层记忆系统（L1 会话记忆 + L2 长期用户记忆）

按数据热度将记忆拆为三层，职责分离：**L1 短期会话记忆**（模型上下文窗口，Redis 托管，热数据允许过期）+ **L2 长期用户记忆**（自建 `user_memory` 表 + pgvector 语义检索，跨会话跨项目的用户画像）+ **业务档案层**（messages 表继续承担历史展示、引用关联与审计，与模型上下文读取路径分离）。

- **分层职责设计**：L1 解决"多轮对话上下文"（按 conversationId 隔离，窗口内热数据），L2 解决"跨会话认识用户"（按 userId 隔离，个性化回答），messages 表解决"业务留痕"（前端历史、answer_sources 关联、审计）——三者内容双写但读取路径分离，互不替代。
- **L1 框架托管装配**：`backend/src/main/java/com/wisread/config/MemoryConfig.java` 第 27-41 行装配 `RedisChatMemoryRepository`（Spring AI Alibaba 官方 starter，复用 Redis 连接配置），第 43-50 行装配 `MessageWindowChatMemory`，窗口 20 条（10 轮问答）自动裁剪，替代原手写历史拼接与 top10 取数。
- **L1 接入问答链路**：`ChatServiceImpl.java` 第 277-279 行以 `conversationId` 为 key 从 Redis 读取记忆窗口，随 Prompt 注入多轮历史（时间正序、已裁剪）；流式回答正常结束（第 332-339 行）与拒答轮次（第 477-479 行）都将本轮"用户原始问题 + 助手回答"写回窗口，`appendToMemory()`（第 499-509 行）尽力而为，写失败只记 warn 不打断 SSE 收尾。
- **L2 表结构设计**：`backend/src/main/resources/db/migration/V7__add_user_memory.sql` 第 5-17 行创建 `user_memory` 表，`VECTOR(1024)` 对齐 Embedding 维度，`status` 三态（ACTIVE 生效 / SUPERSEDED 被新记忆替代 / DELETED 删除）支持"替代/软删"而不物理清除，`category` 区分 FACT/GOAL/CONTEXT；第 26-29 行建两个部分索引——HNSW 向量索引与 user_id 索引均只覆盖 `status='ACTIVE'` 行，活跃记忆才有检索价值，同时降低写入维护成本。
- **L2 语义检索注入**：`UserMemoryServiceImpl.java` 第 81-86 行 `search()`，`searchHits()`（第 170-187 行）用 pgvector `<=>` 余弦距离排序并强制 `user_id = ?` 多租户隔离，低于注入阈值（0.55）的命中不返回；注入复用问答链路改写后 query 的向量（`ChatServiceImpl.java` 第 255-262 行，一次 Embedding 两处复用，省一次调用），总开关关闭时旁路零开销，命中计数器 `wisread.usermemory.inject.hits` 供监控区分注入效果。
- **L2 注入防污染引用**：`ChatServiceImpl.java` 第 564-572 行，长期记忆以独立 `[用户背景]` section 追加到系统提示，明确"可用于个性化回答但不得作为文档依据引用"，且不参与 `[1]..[3]` 编号——防止模型把用户记忆当作文档来源，污染引用溯源体系。
- **L2 写入去重与淘汰**：`UserMemoryServiceImpl.java` 第 91-109 行 `save()`，写入前先向量检索最相似的现有 ACTIVE 记忆，相似度 ≥ 0.92 视为同一条信息，旧记忆置 SUPERSEDED（第 114-118 行，保留原内容供审计）再插入新记忆；`evictOverLimit()`（第 141-152 行）按"importance 升序 + created_at 升序"淘汰超出 200 条上限的活跃记忆为 DELETED，防止单用户记忆无限膨胀。
- **L2 异步沉淀（LLM 提取）**：`backend/src/main/java/com/wisread/service/impl/UserMemoryExtractionServiceImpl.java`，复用 `chatTaskExecutor` 异步执行（第 98-107 行，线程池饱和直接丢弃本次提取）；提取前先做**零成本前置过滤**（第 129-137 行）：内容 < 30 字、命中敏感信息黑名单（第 43-44 行，密码/密钥/身份证等不入库）、不含记忆意图词（第 46-47 行，"记住/我是/我喜欢"等）的轮次直接跳过，拦掉大多数无价值轮次；通过过滤才调 LLM 按固定 JSON Schema 提取（第 49-57 行提示词 + 第 142-160 行调用），解析失败回退为不保存（第 165-174 行），入库前二次敏感校验与字段归一化（第 179-190 行）；全程吞异常记 warn——记忆是旁路能力，任何失败不得影响问答主流程。

### 14. 基础设施与部署

使用 Docker Compose 一键启动 PostgreSQL(pgvector)、MinIO、Redis 三大基础设施，Spring Boot Actuator + Prometheus 暴露监控指标。

- **Docker Compose 编排**：`deploy/docker-compose.yml`，第 2-17 行 PostgreSQL 16 + pgvector（健康检查 `pg_isready`），第 19-30 行 MinIO 对象存储（API 9002 / Console 9003），第 32-44 行 Redis 7（AOF 持久化 + ping 健康检查）。
- **监控端点**：`backend/src/main/resources/application.yml` 第 53-57 行，暴露 `health`、`info`、`prometheus` 端点；第 59-61 行日志 pattern 注入 `traceId` 实现链路追踪。
- **大文件上传配置**：`application.yml` 第 7-9 行 `max-file-size: 100MB`，第 48-51 行 `max-swallow-size: 120MB` 调大 Tomcat 吞入量，使超限请求能正常返回 413 JSON 而非直接断连。

---

## 面试高频问题速查

| 问题 | 定位代码 |
|------|---------|
| RAG 整体流程是怎样的？ | `ChatServiceImpl.java` 第 200-311 行 |
| 如何防止大模型幻觉？ | `ChatServiceImpl.java` 第 80 行 + 第 242-245 行（距离阈值拒答）+ 第 447-484 行（编号引用约束） |
| 向量检索如何实现？ | `VectorIndexingServiceImpl.java` 第 84-114 行（pgvector `<=>` 余弦距离） |
| 文档分块策略是什么？ | `ChunkingServiceImpl.java` 第 41-79 行（800-1200 token + 150 overlap） |
| SSE 流式推送如何实现？ | `ChatServiceImpl.java` 第 267-303 行 + `ConversationController.java` 第 90-98 行 |
| JWT 刷新令牌的安全机制？ | `AuthServiceImpl.java` 第 129-171 行（轮换 + 重放检测） |
| 文档处理失败了怎么办？ | `DocumentProcessingServiceImpl.java` 第 184-211 行（自动重试）+ 第 217-245 行（定时恢复） |
| 并发量上来了如何保护？ | `ChatServiceImpl.java` 第 146 行（Semaphore）+ 第 168-172 行（线程池拒绝 503）+ `AsyncConfig.java` 线程池隔离 |
| 引用来源如何追溯？ | `CitationParsingServiceImpl.java` 第 36-62 行 + `ChatServiceImpl.java` 第 373-382 行 |
| 多轮对话如何处理指代？ | `QueryRewriteServiceImpl.java` 第 56-84 行（问题改写） |
| 记忆系统怎么分层的？ | `MemoryConfig.java` 第 27-50 行（L1 Redis 窗口）+ `V7__add_user_memory.sql`（L2 表） |
| 长期记忆怎么沉淀的？为什么不会越积越多？ | `UserMemoryExtractionServiceImpl.java` 第 112-137 行（前置过滤 + LLM 提取）+ `UserMemoryServiceImpl.java` 第 91-152 行（去重替代 + 超限淘汰） |
| 长期记忆注入后会不会污染引用溯源？ | `ChatServiceImpl.java` 第 255-262 行（检索注入）+ 第 564-572 行（独立 [用户背景] section，不参与编号） |
| 记忆系统挂了会影响问答吗？ | `ChatServiceImpl.java` 第 499-509 行（L1 写回尽力而为）+ `UserMemoryExtractionServiceImpl.java` 第 98-124 行（L2 提取全程吞异常，旁路） |
