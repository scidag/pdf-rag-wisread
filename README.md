# 智阅 Wisread

基于私有知识库的多格式文档智能阅读与问答助手。上传 PDF、DOCX、XLSX、TXT、MD 后，系统完成文本解析、向量化索引，并支持基于原文的多轮问答与引用溯源。

## 功能

- 邮箱 + 密码注册登录，JWT access token + refresh token
- 项目（Project）管理：创建/编辑/删除项目，项目级隔离
- PDF/DOCX/XLSX/TXT/MD 上传、格式/大小校验、异步解析与索引，每项目 5 个文档限制
- 项目级会话：一个会话跨项目内全部已就绪文档检索，多轮问答
- RAG 检索 + Rerank + SSE 流式回答
- 引用来源展示（文档名、编号、页码、原文片段）
- 多轮对话记忆：会话热窗口（Spring AI ChatMemory + Redis）与用户长期记忆（pgvector 语义检索，自动注入与异步沉淀）
- MinIO 文件存储，PostgreSQL + pgvector 向量检索

## 技术栈

- 后端：Java 21、Spring Boot 3.4.5、Spring AI Alibaba、Spring Security、Flyway、PDFBox、Apache POI
- 前端：Next.js 15、React 19、TypeScript、Tailwind CSS
- 基础设施：PostgreSQL 16 + pgvector、Redis、MinIO、Docker Compose

## 目录结构

```text
backend/       Spring Boot 后端
frontend/      Next.js 前端
deploy/        Docker Compose 与环境变量示例
docs/          spec、计划、验收与部署文档
prototypes/    登录注册视觉原型（独立仓库）
```

## 本地启动

### 1. 启动基础设施

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env.example up -d
```

默认端口：

- PostgreSQL：`5433`
- MinIO API：`9002`
- MinIO Console：`9003`

### 2. 启动后端

```bash
cd backend
chcp 65001   # Windows 控制台按 UTF-8 显示日志，避免中文乱码
mvn spring-boot:run
```

后端地址：`http://localhost:8080`

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端地址：`http://localhost:3000`

## 本地 Mock

未配置 DashScope key 时，后端默认使用本地确定性 Embedding 和 Chat Mock，可以完整跑通上传、问答和引用流程。

配置真实模型：

```bash
export DASHSCOPE_API_KEY=your_key
export WISREAD_EMBEDDING_MOCK_ENABLED=false
export WISREAD_CHAT_MOCK_ENABLED=false
```

## API 概览

统一前缀：`/api/v1`

- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `GET /users/me`
- `POST /projects`、`GET /projects`、`GET /projects/{id}`、`PATCH /projects/{id}`、`DELETE /projects/{id}`
- `POST /documents?projectId={id}`
- `GET /documents?projectId={id}`
- `GET /documents/{id}/status`
- `DELETE /documents/{id}`
- `POST /conversations`（body：`{projectId, title?}`）
- `GET /conversations?projectId={id}`
- `GET /conversations/{id}/messages`
- `POST /conversations/{id}/messages`（SSE）

## 测试与验收

- 后端测试：`cd backend && mvn test`
- 前端构建：`cd frontend && npm run build`
- 浏览器验收：`python backend/target/verify_frontend.py`
- 验收指标：见 `docs/acceptance/mvp-acceptance.md`

## 文档

- [技术设计 Spec](docs/superpowers/specs/2026-08-09-wisread-mvp-design.md)
- [部署文档](docs/deployment.md)
- [MVP 验收文档](docs/acceptance/mvp-acceptance.md)
