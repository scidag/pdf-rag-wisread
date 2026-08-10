# Wisread 智阅 MVP 技术设计 Spec

> 状态：待评审
> 日期：2026-08-09
> 上游文档：需求文档mvp.md
> 开发进度：M1 ✅ / M2 ✅ / M3 ✅ / M4 ✅ / M5 ✅ / M6 ⏳ 文档完成，20 题实测待执行
> 当前分支：feat/wisread-mvp-phase1
> 最近提交：41de096（M6：原型登录 + 验收/部署文档）

## 1. 背景与目标

Wisread 是一个基于私有知识库的 PDF 智能阅读与问答助手。用户上传 PDF 后，系统完成文本解析、向量化索引，并支持基于原文的多轮问答与引用溯源。

MVP 目标：验证「上传文档 → AI 理解 → 文档问答 → 来源追溯」完整闭环，同时从 0 建立一套可开源、可面试展示、后续可扩展为 SaaS 的工程骨架。

## 2. 范围

### 2.1 本期实现

- PDF 上传、格式/大小/Token 上限校验
- 异步文本解析、切块、Embedding、写入 pgvector
- 文档列表、状态查看、删除、切换当前文档
- 基于 RAG 的流式问答
- 引用溯源展示（答案编号 + 页码 + 原文）
- 单文档多会话、会话内多轮对话
- 用户注册、登录、退出与 JWT 鉴权
- MinIO 文件存储
- Spring Boot + Spring AI Alibaba 后端
- Next.js + React + Tailwind CSS 前端

### 2.2 本期不实现

- 密码找回、邮箱验证、第三方登录、个人资料编辑
- 多文档知识库与跨文档问答
- OCR、图片理解、表格理解
- PDF 在线阅读器、文档批注、分享链接
- 企业组织、团队、角色、权限、计费
- 用户反馈 UI、成本统计页面

以上不实现的功能，部分数据表（feedback、usage_logs 等）会先建好并预留，本期不写入业务数据。

## 3. 架构决策

| 编号 | 决策 | 说明 |
| --- | --- | --- |
| AD-1 | 单仓库多模块 | backend + frontend + deploy 放在同一仓库，便于开源展示和本地一键启动 |
| AD-2 | Java + Spring AI Alibaba | 后端使用 Java 21 + Spring Boot 3.x，RAG 能力基于 Spring AI Alibaba |
| AD-3 | 真实用户 + JWT | MVP 实现邮箱密码注册登录，Spring Security + JWT access token + refresh token，数据归属 user_id |
| AD-4 | PostgreSQL + pgvector | 结构化数据与向量同库，降低部署复杂度 |
| AD-5 | MinIO 对象存储 | 开发和生产统一使用 MinIO，兼容 S3 API |
| AD-6 | 异步处理管道 | 上传后由后台任务解析和索引，前端轮询或 SSE 获取状态 |
| AD-7 | 系统级引用 | LLM 只能引用系统注入的 chunk 编号，后端校验并绑定来源 |

## 4. 系统架构

```text
Browser (Next.js)
    |
    | HTTP / SSE
    v
Spring Boot (Spring AI Alibaba)
    |-- PostgreSQL + pgvector
    |-- MinIO
    |-- DashScope (LLM + Embedding + Rerank)
    |-- Async Task Executor / Queue
```

数据流：

```text
上传 PDF
  -> 校验并保存 MinIO
  -> 创建 document(UPLOADED) + document_job
  -> PDFBox 提取文本
  -> 按 token 切块
  -> Embedding 并写入 document_chunks
  -> document 状态 READY

提问
  -> query rewrite（多轮）
  -> Query Embedding
  -> pgvector 按 user_id + document_id 召回 Top 10
  -> Rerank Top 3
  -> Prompt 注入 chunk 编号
  -> LLM 流式生成
  -> 后端解析引用编号并绑定 answer_sources
  -> SSE 返回答案 + 来源
```

## 5. 仓库结构

```text
wisread/
  backend/
    pom.xml
    src/main/java/com/wisread/
      WisreadApplication.java
      config/            # Security、Async、VectorStore、MinIO、Model
      controller/        # Auth、Document、Conversation、Health
      service/           # Document、Chunking、Embedding、Rerank、Chat、Citation
      repository/        # Spring Data JPA + pgvector
      entity/            # User、UserSession、Document、DocumentChunk、Conversation、Message、AnswerSource、DocumentJob、UsageLog、Feedback
      dto/               # 请求/响应对象
      exception/         # 统一异常与错误码
      job/               # DocumentProcessingTask
      client/            # MinIO、DashScope 封装
    src/main/resources/
      application.yml
      db/migration/      # Flyway SQL
    src/test/java/       # 单元/集成测试
  frontend/
    package.json
    app/
      layout.tsx
      page.tsx           # 主工作台
    components/
      LoginForm.tsx
      RegisterForm.tsx
      UploadPanel.tsx
      DocumentList.tsx
      ChatPanel.tsx
      SourceCard.tsx
      StatusBadge.tsx
    lib/
      api.ts
      types.ts
  deploy/
    docker-compose.yml
    .env.example
  docs/
    superpowers/
      specs/
  prototypes/
    login-signup-prototype/   # 登录注册原型，独立于主应用
```

## 6. 技术栈

### 6.1 后端

- Java 21
- Spring Boot 3.x
- Spring AI Alibaba（Maven Central 最新稳定版）
- Spring Data JPA
- Flyway
- PostgreSQL 16 + pgvector
- MinIO Java SDK
- Testcontainers（集成测试）
- JUnit 5 + AssertJ + Mockito

### 6.2 模型

- 生成模型：DashScope `qwen-plus`（配置可切换）
- Embedding：DashScope `text-embedding-v3`，维度 1024
- Rerank：DashScope `qwen3-rerank`（或 `gte-rerank-v2`）
- 所有模型 key 通过环境变量注入，禁止前端保存

### 6.3 前端

- Next.js 15+（App Router）
- React 19
- Tailwind CSS
- TypeScript
- fetch / SSE 读取流式回答

## 7. 身份与会话

MVP 实现邮箱 + 密码注册登录，所有数据从第一天就归属 `user_id`。

流程：

1. 注册：提交 `username + email + password`
   - password 使用 BCrypt 哈希
   - email 唯一
2. 登录：校验密码后签发 JWT
   - access token：有效期 15 分钟，前端保存在内存
   - refresh token：有效期 7 天，通过 HttpOnly Cookie 下发
3. 业务接口请求头：`Authorization: Bearer <access token>`
4. refresh token 的 SHA-256 存入 `user_sessions.refresh_token_hash`
5. `POST /auth/refresh` 轮换 refresh token，旧 token 失效
6. 登出删除对应的 `user_session`
7. 所有文档、会话、消息、向量查询统一带 `user_id` 过滤

Refresh Cookie 配置：

```text
name=wisread_refresh
HttpOnly=true
SameSite=Lax
Secure=true（生产）
Max-Age=7 天
Path=/api/v1/auth/refresh
```

access token 不放 localStorage，防止 XSS 窃取；刷新页面后通过 refresh token 重新获取 access token。

## 8. 数据模型

采用需求文档第八节 V2 的表结构。核心关系：

```text
users 1-N user_sessions / documents / conversations / usage_logs
documents 1-N document_chunks / conversations / document_jobs
conversations 1-N messages
messages 1-N answer_sources
```

### 8.1 关键字段约定

- `documents.file_key`：MinIO bucket/object key
- `documents.status`：UPLOADED / PROCESSING / READY / FAILED / DELETED
- `document_chunks.embedding`：`VECTOR(1024)`，与 DashScope text-embedding-v3 保持一致
- `document_chunks.embedding_model_version`：记录模型标识，换模型前必须重建索引
- `document_chunks.page_start/page_end`：chunk 可能跨页
- `answer_sources`：message 与 chunk 的多对多来源关系

### 8.2 索引

```sql
CREATE INDEX idx_documents_user_created ON documents(user_id, created_at);
CREATE INDEX idx_chunks_document ON document_chunks(document_id);
CREATE INDEX idx_chunks_user_document ON document_chunks(user_id, document_id);
CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at);
CREATE INDEX idx_answer_sources_message ON answer_sources(message_id);
CREATE INDEX idx_user_sessions_user ON user_sessions(user_id);
CREATE INDEX ON document_chunks USING hnsw (embedding vector_cosine_ops);
```

### 8.3 删除语义

- 删除文档：删除 MinIO 对象，数据库级联删除 document_chunks、conversations、messages、answer_sources、document_jobs
- 用户删除：MVP 不提供 UI，数据库保留
- MVP 使用硬删除；`DELETED` 状态保留给后续软删除方案

## 9. API 契约

统一前缀：`/api/v1`。除健康检查、注册、登录外，业务接口需要有效 access token；refresh 接口使用 `wisread_refresh` Cookie。

### 9.1 接口列表

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/auth/register` | 注册 |
| POST | `/api/v1/auth/login` | 登录，下发 access token 与 refresh Cookie |
| POST | `/api/v1/auth/refresh` | 刷新 access token |
| POST | `/api/v1/auth/logout` | 登出，删除 user_session |
| GET | `/api/v1/users/me` | 当前用户信息 |
| POST | `/api/v1/documents` | 上传 PDF（multipart） |
| GET | `/api/v1/documents` | 当前用户文档列表 |
| GET | `/api/v1/documents/{documentId}` | 文档详情与状态 |
| DELETE | `/api/v1/documents/{documentId}` | 删除文档 |
| GET | `/api/v1/documents/{documentId}/status` | 处理状态轮询 |
| POST | `/api/v1/conversations` | 创建会话 |
| GET | `/api/v1/conversations?documentId={id}` | 查询文档的会话列表 |
| POST | `/api/v1/conversations/{conversationId}/messages` | 提问，SSE 流式返回 |
| GET | `/api/v1/conversations/{conversationId}/messages` | 读取历史消息 |
| GET | `/api/v1/health` | 健康检查 |

### 9.2 错误码

| HTTP | 场景 |
| --- | --- |
| 400 | 参数错误、空问题 |
| 401 | 会话缺失或过期 |
| 409 | 用户名或邮箱已存在 |
| 404 | 文档/会话不存在或不属于当前用户 |
| 413 | 文件超过 20MB |
| 415 | 文件类型不是 PDF |
| 422 | 校验失败（损坏 PDF、token 超限、扫描件） |
| 429 | 请求过于频繁 |
| 500 | 服务端异常 |

### 9.3 上传响应示例

```json
{
  "id": "doc_01",
  "filename": "contract.pdf",
  "status": "UPLOADED",
  "createdAt": "2026-08-09T12:00:00Z"
}
```

### 9.4 聊天响应示例

```json
{
  "messageId": "msg_100",
  "content": "合同违约金为合同金额的 10% [1]",
  "sources": [
    {
      "index": 1,
      "pageStart": 8,
      "pageEnd": 8,
      "snippet": "若一方违约，应支付合同金额10%的违约金"
    }
  ]
}
```

## 10. 核心流程

### 10.1 上传与索引

1. 前端以 multipart 上传 PDF
2. 后端校验扩展名、MIME、magic bytes、20MB
3. MinIO 保存文件，生成 `file_key`
4. 创建 `document(UPLOADED)` 和 `document_job(PENDING)`
5. 异步任务开始：
   - 状态 PROCESSING
   - PDFBox 提取文本
   - 统计 token，超过 50 万则 FAILED
   - 按 chunk_size 800-1200、overlap 100-200 切块
   - 批量 Embedding
   - 写入 document_chunks
   - 状态 READY
6. 失败自动重试一次；重试前清理已写入的 chunks
7. 前端轮询 `/documents/{id}/status`

### 10.2 问答

1. 校验文档属于当前 user 且状态 READY
2. 如有历史消息，先 query rewrite 补全代词
3. 对重写后问题 Embedding
4. pgvector 检索：

```sql
SELECT content, page_start, page_end, embedding <=> :query_embedding AS distance
FROM document_chunks
WHERE user_id = :userId
  AND document_id = :documentId
ORDER BY distance
LIMIT 10;
```

5. Rerank 取 Top 3
6. 当向量距离（cosine distance）大于 0.65 时，直接返回“文档中没有找到相关信息”（等价 cosine 相似度低于 0.35）
7. 构造 Prompt，chunk 编号为 `[1]..[3]`
8. SSE 流式输出，后端同时解析引用编号
9. 保存 message 与 answer_sources

## 11. RAG 参数

| 参数 | 默认值 |
| --- | --- |
| chunk_size | 800-1200 tokens |
| overlap | 100-200 tokens |
| 召回 | Top 10 |
| Rerank | Top 3 |
| 相似度阈值 | 0.35（cosine） |
| 多轮历史 | 最近 10 条，超过后截断 |
| 生成模型 | qwen-plus |

chunk 使用按段落/句子边界的递归切分，避免把中文句子硬截断；chunk 跨页时记录 `page_start` 和 `page_end`。

## 12. 引用协议

Prompt 约定：

```text
你只能根据提供的文档内容回答。
如果文档没有相关信息，回答“文档中没有找到相关信息”。
引用必须使用系统提供的编号 [1]..[3]。
禁止自行生成不存在的编号。
```

后端处理：

1. 从 SSE 流中提取 `[1]..[3]` 标记
2. 校验编号是否属于本次检索结果
3. 合法编号绑定 `message_id + chunk_id` 写入 `answer_sources`
4. 非法编号丢弃，并记录服务端日志
5. 前端展示答案、页码和原文片段

## 13. 前端规格

### 13.1 页面

- 登录页：邮箱 + 密码，视觉与动效参考 `prototypes/login-signup-prototype`
- 注册页：用户名 + 邮箱 + 密码
- 主工作台单页：左侧文档列表，右侧当前文档问答区
- 顶部/卡片上传入口
- 文档状态：上传中、解析中、索引中、完成、失败
- 会话切换：同一文档可创建多个会话
- 消息气泡：用户消息、AI 消息、来源卡片
- 空状态：未上传文档时提示先上传

### 13.2 交互

- 未登录时跳转登录页
- access token 过期时静默调用 refresh 接口续期
- 上传后立即返回文档卡片并显示状态
- 处理中禁用该文档问答入口
- 问答使用 SSE，边生成边渲染
- 来源卡片显示 `[1]`、页码、原文
- 删除文档需要二次确认

## 14. 非功能要求

### 14.1 性能

- 90% 的 20MB 文字型 PDF 在 60 秒内完成解析与索引
- 问答 P95 < 10 秒
- 首字返回 < 5 秒（SSE）

### 14.2 安全

- API key 只存在后端环境变量
- 密码使用 BCrypt 哈希
- access token 有效期 15 分钟，前端内存保存
- refresh token 只存哈希，通过 HttpOnly Cookie 下发
- 所有查询按 user_id 过滤，越权访问返回 404
- 上传文件校验扩展名、MIME、magic bytes
- 限制上传频率与并发任务数

### 14.3 可观测性

- 请求日志、任务日志、错误追踪
- 关键指标：处理时长、问答延迟、Embedding 失败率、token 用量

## 15. 测试策略

### 15.1 后端

- 单元测试：切块器、引用解析、JWT 解析、文档状态机
- 认证测试：注册、登录、刷新、登出、密码错误、重复注册
- 集成测试：上传-解析-索引-问答全链路
- 数据库测试：Testcontainers + PostgreSQL pgvector
- 安全测试：用户 A 无法访问用户 B 的文档

### 15.2 前端

- 组件测试：UploadPanel、ChatPanel、SourceCard
- E2E：上传 PDF -> 等待 READY -> 提问 -> 看到来源

### 15.3 验收数据

- 固定 5 份测试 PDF
- 20 个人工问题与标准答案
- 答案正确率 >= 85%
- 引用准确率 >= 90%
- 幻觉回答 <= 5%
- 无答案问题返回固定文案

## 16. 实施里程碑

### M1：项目骨架与基础设施（✅ 已完成）

- 初始化 backend/frontend/deploy
- Docker Compose：PostgreSQL + pgvector + MinIO
- Flyway 建表
- 健康检查接口

### M2：注册登录与鉴权（✅ 已完成）

- 注册、登录、刷新、登出
- Spring Security + JWT filter
- 当前用户接口

### M3：上传与索引（✅ 已完成，对应 Phase 2）

- 上传校验与 MinIO 保存
- PDFBox 解析、切块、Embedding、pgvector 写入
- 文档状态轮询与删除

### M4：问答与引用（✅ 已完成，对应 Phase 3）

- 会话、消息表读写
- RAG 检索与 Rerank
- SSE 流式生成
- 引用解析与 answer_sources

### M5：前端工作台（✅ 已完成，对应 Phase 4）

- 登录页与注册页
- 上传、文档列表、状态展示
- 问答、来源卡片、会话切换
- 空状态与错误处理

### M6：测试与验收（⏳ 文档完成，20 题实测待执行）

- 20 题验收
- 性能与安全测试
- README 与部署文档

## 17. 评审确认点

1. 注册登录：邮箱 + 密码，Spring Security + JWT access token + refresh token
2. 模型默认：qwen-plus + text-embedding-v3(1024) + qwen3-rerank
3. 邮箱验证：MVP 不发送验证邮件，注册后直接可用
4. 会话存储：MVP 保存历史消息，刷新页面后仍可通过会话列表恢复（此点调整了需求文档 F10 的“刷新清空上下文”，需评审确认）
5. 单文档多会话：本期实现
6. 文档上限：每个 user 最多 5 个 PDF，超出后上传返回 400

## 18. 开发进度记录

### 2026-08-09 Phase 1：项目骨架与注册登录

- 提交：`4793fd6 feat: initialize Wisread backend with JWT auth`
- 完成：git 仓库、Docker Compose、Flyway V1、Spring Boot 骨架、JWT 注册登录/刷新/登出、MinIO 客户端

### 2026-08-09 Phase 2：PDF 上传与向量索引

- 提交：`55f5c86 feat: add PDF upload and vector indexing`
- 完成：上传校验、MinIO 存储、PDFBox 解析、切块、Embedding、pgvector 写入、文档列表/状态/删除、用户隔离
- 验证：全量测试通过，上传样例 PDF 达到 READY，document_chunks 已落库

### 2026-08-10 Phase 3：RAG 问答与引用溯源

- 提交：`604c57b feat: add RAG chat with SSE and citations`
- 完成：会话/消息/answer_sources 实体与接口、query rewrite、向量召回 Top10、Rerank Top3、SSE 流式问答、引用解析与落库、本地 Chat/Embedding Mock
- 验证：全量测试通过，SSE 返回 `[1]` 来源，answer_sources 已落库，用户隔离返回 404

### 2026-08-10 Phase 4：前端工作台

- 提交：`7b32e9c feat: add Next.js frontend workbench`
- 完成：登录/注册、文档上传与状态轮询、会话列表、SSE 流式消息、来源卡片、移动端适配、CORS
- 验证：Next.js 构建通过，Playwright 桌面/移动端登录、提问、来源展示全部通过

### 2026-08-10 M6：验收文档与部署文档

- 提交：`41de096 feat: port prototype auth and add M6 acceptance docs`
- 完成：登录注册按原型重做、README、部署文档、MVP 验收文档、20 题问题模板、Playwright 桌面/移动端验证
- 待执行：准备 5 份正式测试 PDF 和 20 题标准答案，完成答案正确率/引用准确率/幻觉率实测
