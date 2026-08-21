# 项目级对话改造设计文档

> 版本：v1.0  日期：2026-08-15  状态：待审阅
>
> 依据原型：`prototypes/home-workspace-prototype/index.html`
> 已确认决策：1A（新建 projects 表）｜2A（项目级跨文档检索）｜3 每项目算额度｜4 加表，旧数据不处理｜5 `/projects/[projectId]`｜6 前后端全改

---

## 1. 目标与范围

### 1.1 目标
把当前"文档级"会话升级为"项目级"会话：

- 引入 **项目（Project）** 作为一等实体，一个项目下可挂多个 PDF 文档。
- 会话从绑定单个文档改为绑定**项目**，一个会话提问时跨项目内全部已 READY 文档检索。
- 前端从单层工作区改为 **项目列表 → 项目内（文档列表 + 会话列表 + 聊天）** 两层结构，对齐原型。
- PDF 上限从"每用户 5 个"改为"每项目 N 个"。

### 1.2 不在本次范围
- ~~首页数据概览（`view-home`）、最近问答面板~~ —— 2026-08-16 用户补充需求后已实现为 `app/home/page.tsx`（问候 + 统计卡 + 最近文档/问答 + 快捷操作），见 `docs/tasks.md` 阶段七 H1–H4。
- 旧数据迁移：用户既有 documents/conversations 数据不做回填，按"旧数据不处理"决策。
- 多文档知识库的 V2 高级能力（文档分组、跨项目检索、知识库命名）——本次只做单项目内多文档。

### 1.3 决策溯源
| 决策项 | 选择 | 理由 |
|---|---|---|
| 项目实体 | 真建 `projects` 表 | 需求文档第 790 行"表结构从第一天保持一致避免后期大迁移" |
| 会话归属 | 项目级，跨项目内文档检索 | "项目级对话"的核心语义 |
| 文档额度 | 每项目 5 个 | 用户指定；原型项目卡文档数加总可超 5，按项目算才自洽 |
| 数据迁移 | 不处理旧数据 | 当前在 feature 分支未合并，无生产数据 |
| 前端路由 | `/projects/[projectId]` 及子路由 | 用户指定 |
| 改动范围 | 前后端全改 | 用户指定 |

---

## 2. 数据库设计

### 2.1 新增 `projects` 表

```sql
CREATE TABLE projects (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_projects_user_created ON projects(user_id, created_at);
```

### 2.2 `documents` 表改动
新增 `project_id` 列（NOT NULL，新建文档必须归属某项目）：

```sql
ALTER TABLE documents ADD COLUMN project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE;
CREATE INDEX idx_documents_project ON documents(project_id);
```

> **关于旧数据**：按决策 4，旧数据不处理。`project_id` 对存量行允许为 NULL（不加 NOT NULL 约束），新上传文档强制非空。应用层对 `project_id IS NULL` 的旧文档视为孤儿，不在新 UI 展示，可后续手工清理。

### 2.3 `conversations` 表改动
会话从文档级改为项目级：

```sql
ALTER TABLE conversations ADD COLUMN project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE;
-- document_id 列保留但改为可空（不再用作检索范围，仅保留兼容）
ALTER TABLE conversations ALTER COLUMN document_id DROP NOT NULL;
CREATE INDEX idx_conversations_project ON conversations(project_id);
```

> `document_id` 列保留可空，不删除，避免迁移复杂度。新会话 `document_id = NULL`，`project_id` 必填。

### 2.4 `answer_sources` 冗余 `document_id`
跨文档检索后，来源需标注来自哪个文档，前端来源卡显示"文档名 · 第 N 页"：

```sql
ALTER TABLE answer_sources ADD COLUMN document_id BIGINT REFERENCES documents(id) ON DELETE CASCADE;
```

### 2.5 文档额度约束
从"每用户 5 个"改为"每项目 5 个"（应用层校验，非 DB 约束）：
- `DocumentService.upload` 校验改为 `countByProjectId(projectId) >= 5`。

### 2.6 迁移文件
新增 `V4__add_projects.sql`，内容为上述 2.1–2.4 的 DDL。不重写 V1。

---

## 3. 后端 API 设计

### 3.1 新增 ProjectController

路由前缀：`/api/v1/projects`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/` | 创建项目 `{name, description?}` |
| GET | `/` | 当前用户项目列表 |
| GET | `/deleted` | 当前用户已删除（回收站）项目列表 |
| GET | `/{projectId}` | 项目详情（含文档数、会话数统计） |
| PATCH | `/{projectId}` | 更新项目 `{name?, description?}` |
| PATCH | `/{projectId}/restore` | 恢复已删除项目 |
| DELETE | `/{projectId}` | 删除项目（逻辑删除，仅置 `deleted_at`，数据保留） |

所有方法从 Authentication 取 userId，按 `user_id` 隔离。

### 3.2 DocumentController 改动
- `POST /api/v1/documents`（上传）增加必填参数 `projectId`。
- `GET /api/v1/documents` 改为 `GET /api/v1/documents?projectId={id}`，返回项目内文档。
- 额度校验按 projectId。

### 3.3 ConversationController 改动
- `POST /api/v1/conversations` 请求体从 `{documentId, title}` 改为 `{projectId, title?}`。
- `GET /api/v1/conversations?projectId={id}` 按 projectId 过滤。
- `POST /api/v1/conversations/{conversationId}/messages`（聊天）不变，但内部检索逻辑改为项目级。

### 3.4 检索逻辑改造（核心）

**`VectorIndexingService.searchWithContent`** 当前签名：
```java
searchWithContent(Long userId, Long documentId, float[] queryEmbedding, int limit)
```
改为项目级多文档检索：
```java
searchWithContent(Long userId, Long projectId, float[] queryEmbedding, int limit)
```
SQL 从 `WHERE user_id = ? AND document_id = ?` 改为：
```sql
WHERE user_id = ? AND document_id IN (
    SELECT id FROM documents WHERE project_id = ? AND status = 'READY'
)
```
> 只检索 READY 状态文档，避免命中仍在处理中的 chunk。

**`ChatService.processAsk`** 改动：
- 从 `conversation.getDocumentId()` 改为 `conversation.getProjectId()`。
- 检索调用改为传 `projectId`。
- `ChunkSearchResult` 新增 `documentId` 与 `filename` 字段，prompt 注入与来源绑定需带上文档名。

### 3.5 DTO 改动
| DTO | 改动 |
|---|---|
| `CreateProjectRequest` | 新增 `{name, description?}` |
| `ProjectResponse` | 新增 `{id, name, description, documentCount, conversationCount, createdAt, updatedAt}` |
| `CreateConversationRequest` | `documentId` → `projectId` |
| `ConversationResponse` | `documentId` → `projectId` |
| `DocumentResponse` | 新增 `projectId` 字段 |
| `SourceResponse` | 新增 `documentId`、`filename` 字段 |
| `ChunkSearchResult` | 新增 `documentId`、`filename` 字段 |

### 3.6 删除项目软删
删除项目时：
1. 仅将 `projects.deleted_at` 置为当前时间
2. documents、conversations、messages、chunks、向量、MinIO 文件全部保留
3. 项目列表、详情及子资源查询统一过滤 `deleted_at IS NULL`
4. 恢复时清空 `deleted_at`，关联数据原样可用

---

## 4. 前端设计

### 4.1 路由结构

```
/                       → 重定向到 /projects（已登录）或 /login
/login                  → 登录
/register               → 注册
/projects               → 项目列表页（对应原型 view-workspace）
/projects/[projectId]   → 项目内页（对应原型 view-project）
  - 侧边栏：文档列表 + 会话列表
  - 主区：聊天
/projects/[projectId]/chat/[conversationId]
  → 项目内指定会话（可选，MVP 可先用 query param ?conv=）
```

采用 `/projects/[projectId]?conv={conversationId}`，会话切换不换路径，仅 query 变化。

### 4.2 页面与组件拆分

**`app/projects/page.tsx`**（项目列表）
- 顶部 topbar（品牌、导航、新建项目、头像）
- 项目卡片网格（名称、描述、文档数、会话数、进入按钮）
- 空态：无项目时引导新建

**`app/projects/[projectId]/page.tsx`**（项目内页）
- 布局：左侧 Sidebar（文档 + 会话）+ 右侧 ChatPanel
- Sidebar 数据：`listDocuments(projectId)` + `listConversations(projectId)`
- ChatPanel 复用现有组件，但来源卡需显示文档名

**新建组件：**
- `ProjectCard.tsx` — 项目卡片
- `ProjectCreateDialog.tsx` — 新建项目弹窗
- `ProjectSidebar.tsx` — 项目内页侧边栏（文档列表 + 会话列表 + 上传）

**改造组件：**
- `Sidebar.tsx` → 拆分为 `ProjectSidebar.tsx`，移除文档级会话逻辑
- `SourceCard.tsx` → 显示 `filename · 第 N 页`
- `ChatPanel.tsx` → 空态文案改为"选择会话或新建会话开始提问"

**废弃/保留：**
- `app/workspace/page.tsx` → 重定向到 `/projects`，保留路由兼容

### 4.3 API 客户端改造（`lib/api.ts`）
新增：
```ts
listProjects(): Promise<Project[]>
createProject(req): Promise<Project>
getProject(id): Promise<Project>
updateProject(id, req): Promise<Project>
deleteProject(id): Promise<void>

// 改造
listDocuments(projectId): Promise<Document[]>
uploadDocument(file, projectId): Promise<Document>
createConversation(projectId, title?): Promise<Conversation>
listConversations(projectId): Promise<Conversation[]>
```

### 4.4 类型改造（`lib/types.ts`）
新增 `Project` 接口；`Document` 加 `projectId`；`Conversation` 的 `documentId` → `projectId`；`Source` 加 `documentId`、`filename`。

### 4.5 视觉主题

**决策：切换到原型深色主题。** 本次功能改造同时完成视觉对齐：
- CSS 变量照搬原型：`--bg: #040810`、`--accent: #3a7fff` 等
- 字体：Manrope + Noto Sans SC + IBM Plex Mono（原型同款）
- 全局样式在 `app/globals.css` 定义变量，组件用 Tailwind 任意值或 CSS 变量引用
- 登录页、项目列表、项目内页全部深色化

---

## 5. 关键改动清单

### 后端（13 处）
1. 新增 `V4__add_projects.sql` 迁移
2. 新增 `Project` 实体
3. 新增 `ProjectRepository`
4. 新增 `ProjectService`（含级联删除）
5. 新增 `ProjectController` + DTO
6. `Document` 实体加 `projectId`
7. `DocumentRepository` 加 `findByProjectId`、`countByProjectId`
8. `DocumentService.upload` 加 `projectId` 参数 + 项目级额度校验
9. `DocumentController` 上传/列表接口加 `projectId`
10. `Conversation` 实体 `documentId` → `projectId`
11. `ConversationRepository` 查询改 `projectId`
12. `ConversationService.create/list` 改 `projectId`
13. `ChatService` + `VectorIndexingService` 检索改项目级多文档

### 前端（10 处）
1. `app/projects/page.tsx` 项目列表页
2. `app/projects/[projectId]/page.tsx` 项目内页
3. `ProjectCard.tsx`、`ProjectCreateDialog.tsx`、`ProjectSidebar.tsx` 新组件
4. `SourceCard.tsx` 显示文档名
5. `ChatPanel.tsx` 空态文案
6. `lib/api.ts` 新增项目 API + 改造文档/会话 API
7. `lib/types.ts` 新增 Project 类型 + 改造 Document/Conversation/Source
8. `app/page.tsx` 重定向逻辑
9. `app/workspace/page.tsx` 重定向到 `/projects`
10. 顶部导航 topbar 组件（品牌 + 新建项目 + 头像）

---

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| 跨文档检索 SQL `document_id IN (...)` 在文档数多时性能下降 | 项目级文档数有上限（5 个），子查询规模可控；如需优化可改 JOIN |
| 删除项目级联清理复杂，易遗漏 MinIO 文件 | 在 `ProjectService.delete` 中显式遍历文档删文件，事务回滚时文件可能残留——记 TODO 定时清理 |
| `conversations.document_id` 列保留可空，新旧逻辑并存易混淆 | 应用层新会话一律不写 `document_id`，仅读 `project_id`；旧会话因 `project_id IS NULL` 不在新 UI 展示 |
| 前端浅色 vs 原型深色 | 待用户确认是否本次切换主题 |
| `answer_sources.document_id` 存量行无值 | 仅影响旧消息来源展示，旧数据不处理决策下可接受 |

---

## 7. 已确认决策

1. **前端主题**：切换到原型深色主题，本次功能改造同时完成视觉对齐。
2. **会话路由**：`/projects/[projectId]?conv={conversationId}`（query param）。
3. **每项目文档上限**：5 个。
4. **项目描述字段**：支持创建时填写（可选）。
