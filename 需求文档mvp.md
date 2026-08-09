# 智阅（Wisread）MVP 产品需求文档

版本：V1.0
 产品类型：AI 文档阅读助手
 目标：验证用户通过 AI 对 PDF 文档进行深度阅读和问答的可行性

------

# 一、产品概述

| 项目     | 内容                                                         |
| -------- | ------------------------------------------------------------ |
| 产品名称 | 智阅 Wisread                                                 |
| 产品定位 | 基于私有知识库的 PDF 智能阅读与问答助手                      |
| 产品目标 | 用户上传 PDF 后，通过自然语言向文档提问，获得基于原文依据的 AI 回答 |
| 核心价值 | 降低长文档阅读成本，提高信息检索效率                         |
| MVP目标  | 验证「上传文档 → AI理解 → 文档问答 → 来源追溯」完整闭环      |
| 产品形态 | Web 应用                                                     |

------

# 二、用户角色

## 普通用户

使用场景：

-  阅读论文 
-  分析技术文档 
-  查看合同 
-  阅读行业报告 
-  学习资料总结 

MVP阶段：

实现：

-  用户注册 
-  用户登录 
-  用户退出 

不实现：

-  权限管理 
-  企业空间 

但必须保证：

> 不同用户之间的数据隔离。

实现方式：

JWT access token + refresh token（详见第十三节）。

users、user_sessions 本期启用；feedback、usage_logs 等表先建好，本期暂不写入业务数据（详见第八节 V2）。

------

# 三、MVP核心流程

整体流程：



```
用户上传PDF

↓

文件校验

↓

PDF文本解析

↓

文本切块

↓

Embedding向量化

↓

存入向量数据库


↓

用户提问

↓

问题向量化

↓

相关内容检索

↓

LLM生成回答


↓

返回：

答案

+

来源文本

+

页码信息
```



------

# 四、功能需求

------

# 模块一：PDF上传

## F1 PDF文件上传

优先级：P0

用户可以：

-  点击上传按钮 
-  选择PDF文件 

限制：

| 限制       | 要求          |
| ---------- | ------------- |
| 文件类型   | PDF           |
| 最大大小   | 20MB          |
| 最大文本量 | 50万token以内 |

上传失败：

提示：

-  文件格式错误 
-  文件过大 
-  文件损坏 

------

## F2 文件处理状态

上传后显示状态：

| 状态   | 说明          |
| ------ | ------------- |
| 上传中 | 文件传输阶段  |
| 解析中 | PDF文本处理中 |
| 索引中 | 生成向量      |
| 完成   | 可以开始问答  |
| 失败   | 处理异常      |

------

# 模块二：文档解析与知识库构建

------

# F3 PDF文本解析

优先级：P0

后台自动执行。

处理内容：

-  PDF文本提取 
-  页码保留 
-  文档信息保存 

保存metadata：



```
{
 filename:"xxx.pdf",
 page:5,
 content:"xxx"
}
```



------

# F4 文本切分

优先级：P0

采用固定规则切分。

默认参数：



```
chunk_size:
800-1200 tokens


overlap:
100-200 tokens
```



每个chunk保存：



```
{
text:"",
page:8,
document_id:"xxx"
}
```



------

# F5 向量化

优先级：P0

调用Embedding模型。

流程：



```
chunk文本

↓

Embedding

↓

向量

↓

Vector Database
```



支持：

-  pgvector 
-  Milvus 
-  Chroma 

------

# F6 失败重试

优先级：P1

处理失败：

自动重试一次。

失败后：

状态：



```
FAILED
```



提示用户：

> 文档解析失败，请重新上传。

------

# 模块三：智能问答

------

# F7 文档问答

优先级：P0

用户输入：

自然语言问题。

例如：

> 这个合同的违约责任是什么？

系统流程：



```
用户问题

↓

Query Embedding

↓

向量检索 Top10

↓

Rerank Top3

↓

LLM回答
```



------

# F8 RAG增强

优先级：P0

检索策略：

## 第一阶段

向量召回：

Top 10

## 第二阶段

排序：

Top 3

## 第三阶段

生成回答。

------

# F9 防幻觉机制

优先级：P0

Prompt要求：



```
你只能根据提供的文档内容回答。

如果文档没有相关信息：

回答：
"文档中没有找到相关信息"

禁止自行推测。
```



------

# F10 对话上下文

优先级：P1

MVP：

支持当前页面内连续对话。

例如：

用户：

> 作者是谁？

AI：

> 张三

用户：

> 他什么时候发表的？

AI：

结合上下文回答。

刷新页面：

上下文清空。

------

# 模块四：引用溯源

------

# F11 来源展示

优先级：P0

AI回答必须包含来源。

展示：



```
AI回答：

合同违约金为合同金额10%。

来源：

[1]

第8页

原文：

"若一方违约，应支付合同金额10%的违约金"
```



------

# F12 引用生成机制

优先级：P0

禁止：

让LLM自由生成引用编号。

采用：

系统生成。

流程：



```
Retriever返回chunk

↓

生成chunk id

↓

LLM回答

↓

后端绑定来源
```



保证：

引用真实存在。

------

# 模块五：文档管理

------

# F13 当前文档管理

优先级：P0

MVP限制：

单用户最多保存：

5个PDF。

支持：

-  查看 
-  删除 
-  切换当前文档 

------

# 六、非功能需求

------

# 性能

## 文档处理

目标：

90%的普通PDF：

60秒内完成。

测试条件：

-  20MB以内 
-  文字型PDF 

------

## 问答响应

目标：

P95：

<10秒

体验：

-  首字返回 <5秒 

------

# 可用性

要求：

用户无需教程即可完成：



```
上传PDF

↓

提出问题

↓

获得答案
```



------

# 安全

要求：

## 文件隔离

不同用户：

不能访问其他文档。

## Key安全

禁止：

前端保存：

-  OpenAI Key 
-  Embedding Key 

------

# 七、技术方案建议

------

## 前端

推荐：



```
Next.js

React

Tailwind CSS
```



------

## 后端

推荐：



```
Java 17/21 + Spring Boot 3.x + Spring AI Alibaba
```



负责：

-  文件上传与 Session 鉴权 
-  异步文档解析与索引 
-  RAG 问答流程（检索、重排、生成、引用校验） 
-  API 管理与流式响应 

Spring AI Alibaba 组件：

-  ChatClient / ChatModel：默认 DashScope 通义千问，其他模型通过 Spring AI 适配 
-  PagePdfDocumentReader / PDFBox：PDF 文本提取 
-  TextSplitter：文档切块 
-  EmbeddingModel：向量化 
-  VectorStore：pgvector 写入与检索 
-  Rerank：DashScope Rerank / bge-reranker 

------

## 文件存储

开发环境：

MinIO（本地/Docker）

生产：

MinIO 或兼容 S3 的 OSS / S3

------

## PDF解析

推荐：

PDFBox / Spring AI PagePdfDocumentReader

------

## Embedding

MVP 必须固定单一模型并记录版本：

国产：

-  BGE 
-  DashScope text-embedding-v3 

海外：

-  OpenAI Embedding 

要求：

-  query 与 chunk 使用同一个 embedding 模型 
-  向量维度、距离度量写入配置；换模型前重建索引 

------

## Vector DB

MVP：

推荐：

PostgreSQL + pgvector（Spring AI VectorStore 实现）

原因：

-  简单 
-  成本低 
-  易部署 

------

## LLM

支持：

-  GPT-4.1 
-  Claude 
-  DeepSeek 

------

# 八、数据库设计（MVP 简化版）

## Document表



```
documents

id

filename

size

status

created_at
```



------

## Chunk表



```
chunks

id

document_id

content

page

embedding
```



------

## Conversation表



```
messages

id

document_id

role

content

created_at
```



------

## 数据库设计 V2（完整表结构，预留字段）

从 0 开发按 V2 建表。MVP 阶段先建好全部表，users、user_sessions 本期启用；feedback、成本统计等功能暂不启用，相关字段可以先不写业务数据；核心表结构和外键从第一天保持一致，避免后期大迁移。

### users 用户表

```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(500),
    status SMALLINT DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### user_sessions 用户登录会话表

```sql
CREATE TABLE user_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    refresh_token_hash VARCHAR(255),
    device VARCHAR(100),
    ip_address VARCHAR(50),
    expires_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### documents 文档表

```sql
CREATE TABLE documents (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    filename VARCHAR(255),
    file_key VARCHAR(500),
    file_size BIGINT,
    page_count INT,
    token_count INT,
    status VARCHAR(20),
    retry_count INT DEFAULT 0,
    error_message TEXT,
    embedding_model_version VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

`file_key` 存 MinIO 的 bucket/object；如需对外 URL，可另存 `file_url` 或按需生成预签名 URL。`status` 枚举：UPLOADED / PROCESSING / READY / FAILED / DELETED；前端展示的上传中、解析中、索引中可映射到 PROCESSING 阶段。

### document_chunks 文档分块表

```sql
CREATE TABLE document_chunks (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    page_start INT,
    page_end INT,
    token_count INT,
    embedding VECTOR(1024),
    embedding_model_version VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

`embedding` 维度按所选模型调整（OpenAI/BGE/DashScope 不同），并记录 `embedding_model_version`，换模型前需要重建索引。

### conversations 会话表

```sql
CREATE TABLE conversations (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    document_id BIGINT NOT NULL REFERENCES documents(id),
    title VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### messages 消息表

```sql
CREATE TABLE messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id),
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### answer_sources 引用溯源表

```sql
CREATE TABLE answer_sources (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES messages(id),
    chunk_id BIGINT NOT NULL REFERENCES document_chunks(id),
    relevance_score FLOAT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### document_jobs 文档处理任务表

```sql
CREATE TABLE document_jobs (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents(id),
    job_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt INT DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### usage_logs 用量成本表

```sql
CREATE TABLE usage_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    model VARCHAR(100),
    input_tokens INT,
    output_tokens INT,
    cost_estimate NUMERIC(12,6),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### feedback 用户反馈表

```sql
CREATE TABLE feedback (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    message_id BIGINT NOT NULL REFERENCES messages(id),
    rating SMALLINT,
    comment TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

建议索引：

```sql
CREATE INDEX idx_documents_user_created ON documents(user_id, created_at);
CREATE INDEX idx_chunks_document ON document_chunks(document_id);
CREATE INDEX idx_chunks_user_document ON document_chunks(user_id, document_id);
CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at);
CREATE INDEX idx_answer_sources_message ON answer_sources(message_id);
CREATE INDEX idx_user_sessions_user ON user_sessions(user_id);
CREATE INDEX ON document_chunks USING hnsw (embedding vector_cosine_ops);
```

预留原则：

- MVP 开放注册登录，用户注册后自动创建 user_session 会话记录
- feedback、usage_logs 等表先建好，MVP 阶段可以不写入业务数据
- 删除文档时级联删除 document_chunks、messages、answer_sources，并同步删除 MinIO 中的文件
- SaaS 化阶段再扩展 organizations、teams、roles、permissions、billing，无需重构核心用户与文档模型

# 九、验收标准

## 上传测试

条件：

20MB文字PDF

通过：

60秒内：



```
status=READY
```



------

## 问答测试

准备：

20个人工问题。

评价：

| 指标       | 目标 |
| ---------- | ---- |
| 答案正确率 | ≥85% |
| 引用准确率 | ≥90% |
| 幻觉回答   | ≤5%  |

------

## 无答案测试

问题：

文档不存在的信息。

要求：

回答：

> 文档中没有找到相关信息。

------

## 安全测试

测试：

不同用户访问。

结果：

无法读取其他文档。

------

# 十、明确不包含功能

MVP不实现：

❌ 多文档知识库

❌ OCR扫描PDF

❌ 图片理解

❌ 表格理解

❌ PDF在线阅读器

❌ 文档批注

❌ 分享链接

❌ 密码找回 / 邮箱验证 / 第三方登录

❌ 企业权限

------

# 十一、未来版本规划

## V2 多文档知识库

能力：



```
上传多个PDF

↓

建立知识库

↓

跨文档问答
```



------

## V3 多模态理解

增加：

-  OCR 
-  图片 
-  表格 

------

## V4 企业版

增加：

-  企业组织 / 多租户 
-  权限 
-  团队空间 

------

# 十二、MVP成功标准

产品验证成功：

满足：

1.  用户可以独立完成上传和问答流程 
2.  AI回答大部分基于原文 
3.  引用来源可信 
4.  用户愿意继续使用

# 十三、MVP 实现补充（技术细节）

## 1. Session 隔离与鉴权

采用邮箱 + 密码注册登录，Spring Security + JWT：

- access token：有效期 15 分钟，请求头 Authorization: Bearer <token>
- refresh token：有效期 7 天，通过 HttpOnly Cookie 下发
- user_sessions 只存 refresh_token_hash（SHA-256），不存明文
- 查询统一执行 document_id + user_id 双重过滤
- 5 个 PDF 上限按 user_id 统计
- 登出时删除 user_session，refresh token 立即失效
- 鉴权只解决“请求者是谁”，数据隔离仍靠每条 SQL 的 user_id 过滤

## 2. 异步处理管道

- 上传后状态流转：UPLOADING → PARSING → INDEXING → READY / FAILED
- MVP 使用 Spring Boot @Async + 任务执行器；生产建议接入 RocketMQ/Kafka
- 前端轮询 GET /api/documents/{id}/status，或使用 SSE 推送状态
- 失败自动重试一次；重试前必须清理已写入的部分 chunk
- 记录 retry_count、error_message，避免重复索引
- 应用重启后可从 INDEXING/FAILED 状态恢复或标记失败

## 3. API 契约（建议）

- POST /api/auth/register 注册
- POST /api/auth/login 登录
- POST /api/auth/refresh 刷新 access token
- POST /api/auth/logout 登出
- POST /api/documents/upload 上传 PDF
- GET /api/documents 当前用户文档列表
- GET /api/documents/{id} 文档详情与状态
- DELETE /api/documents/{id} 删除文档、向量、消息、物理文件
- POST /api/documents/{id}/chat 问答，SSE 流式返回
- GET /api/documents/{id}/status 处理状态轮询
- 错误码统一：401 会话失效、404 文档不存在或无权访问、413 文件过大、422 校验失败

## 4. RAG 与引用协议

- 向量检索必须按 user_id + document_id 过滤
- 默认 Top 10 → Rerank Top 3
- 设置相似度阈值，低于阈值回答“文档中没有找到相关信息”
- query 与 chunk 使用同一 embedding 模型，记录 embedding_model_version
- 多轮对话先做 query rewrite，再用重写后的问题检索
- 引用协议：
  - 检索结果编号 [1]..[3] 注入 prompt
  - 要求 LLM 只能引用给定编号
  - 后端解析答案中的编号并校验存在性
  - 绑定页码与原文，非法引用丢弃并记录告警

## 5. 文件校验与解析边界

- 客户端与服务端双重校验：扩展名、MIME、magic bytes、20MB
- 服务端解析后统计 token 数，超过 50 万 token 返回失败
- 加密 PDF、损坏 PDF、零文本 PDF（扫描件）给出明确错误
- 文字型 PDF 使用 PDFBox / Spring AI PagePdfDocumentReader
- 20MB、50 万 token、60 秒完成使用同一份测试 PDF 定义验收

## 6. 数据库补充

完整表结构见“八、数据库设计 V2”，新增：users、user_sessions、documents、document_chunks、conversations、messages、answer_sources、document_jobs、usage_logs、feedback。

- 所有表先建好；MVP 阶段使用 users/user_sessions/documents/document_chunks/conversations/messages/answer_sources 核心流程，feedback、usage_logs 暂不启用
- 预留字段可以先不写入业务数据，但表结构和外键从第一天保持一致
- user_sessions 只存 refresh_token_hash（SHA-256），不存明文 Token
- document_chunks 冗余 user_id，用于向量检索过滤
- 删除文档时级联删除 chunks/messages/answer_sources，并同步删除 MinIO 中的文件
- 建议索引：(documents.user_id)、(document_chunks.document_id)、(document_chunks.user_id, document_id)、(messages.conversation_id, created_at)

## 7. 验收指标定义

- 固定 5 份测试 PDF 与 20 个人工问题，准备标准答案
- 答案正确率：人工判定答案与标准答案一致
- 引用准确率：引用存在，且页码、原文正确
- 幻觉率：答案中出现文档不存在的关键信息
- 无答案测试：低于相似度阈值必须返回固定文案
- 会话隔离测试：用户 A 访问用户 B 文档必须 404

## 8. 部署与运维

- Docker Compose：Java 应用 + PostgreSQL(pgvector) + MinIO
- 使用 Flyway/Liquibase 管理数据库迁移
- API Key 通过环境变量注入，禁止前端保存
- 接入日志、错误追踪、监控告警
- 文档删除后同步清理文件与向量
