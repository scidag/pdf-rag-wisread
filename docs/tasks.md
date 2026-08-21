# 项目级对话改造任务清单

> 关联设计文档：`docs/design-project-level-conversation.md`
> 状态：进行中。T1–T32 全部完成并验证；阶段七首页与布局对齐完成（H1–H5）；T33/T34 待运行时验证（需 Docker 基础设施，当前环境未运行）。
> 更新记录：
> - 2026-08-16 按工作区代码核对进度回填勾选；完成 T30；`mvn -o test` BUILD SUCCESS（26 测试全过）、`npm run build` 通过；删除废弃组件 Sidebar/ConversationList/UploadPanel/DocumentList；同步 README.md 与 AGENT.md。
> - 2026-08-16 按原型补充首页与全屏布局（阶段七）：新增 `app/home/page.tsx`、Topbar 双导航、`/` 重定向 `/home`、项目列表全宽；`npm run build` 复验通过。

---

## 阶段一：后端数据层（迁移 + 实体 + 仓库）

- [x] T1 创建 `V4__add_projects.sql` 迁移
  - 新建 `projects` 表 + 索引
  - `documents` 加 `project_id` 列 + 索引（不加 NOT NULL，兼容旧数据）
  - `conversations` 加 `project_id` 列 + 索引；`document_id` DROP NOT NULL
  - `answer_sources` 加 `document_id` 列
  - 文件路径：`backend/src/main/resources/db/migration/V4__add_projects.sql`

- [x] T2 新增 `Project` 实体
  - 字段：id, userId, name, description, createdAt, updatedAt
  - 路径：`backend/src/main/java/com/wisread/entity/Project.java`

- [x] T3 改造 `Document` 实体
  - 加 `projectId` 字段（Long，可空）
  - 路径：`backend/src/main/java/com/wisread/entity/Document.java`

- [x] T4 改造 `Conversation` 实体
  - 加 `projectId` 字段；`documentId` 改可空
  - 路径：`backend/src/main/java/com/wisread/entity/Conversation.java`

- [x] T5 新增 `ProjectRepository`
  - 方法：`findByUserIdOrderByCreatedAtDesc`、`findByUserIdAndId`、`countByUserId`
  - 路径：`backend/src/main/java/com/wisread/repository/ProjectRepository.java`

- [x] T6 改造 `DocumentRepository`
  - 加 `findByProjectIdOrderByCreatedAtDesc`、`countByProjectId`
  - 保留 `findByUserIdOrderByCreatedAtDesc`（首页/全局用）
  - 路径：`backend/src/main/java/com/wisread/repository/DocumentRepository.java`

- [x] T7 改造 `ConversationRepository`
  - 加 `findByUserIdAndProjectIdOrderByUpdatedAtDesc`
  - 保留 `findByUserIdAndId`
  - 移除/废弃 `findByUserIdAndDocumentIdOrderByUpdatedAtDesc`
  - 路径：`backend/src/main/java/com/wisread/repository/ConversationRepository.java`

---

## 阶段二：后端服务与检索

- [x] T8 新增 `ProjectService`
  - `create(userId, req)`、`list(userId)`、`get(userId, id)`、`update(userId, id, req)`、`delete(userId, id)`
  - 删除项目为逻辑删除：仅置 `deleted_at`，文档、会话、向量、MinIO 文件全部保留，提供回收站恢复
  - 路径：`backend/src/main/java/com/wisread/service/ProjectService.java`

- [x] T9 改造 `DocumentService`
  - `upload(userId, projectId, file)`：校验项目归属 + 项目级额度（`countByProjectId >= 5`）
  - `listByProject(userId, projectId)`
  - 保留 `list(userId)` 全量查询（首页用）
  - 路径：`backend/src/main/java/com/wisread/service/DocumentService.java`

- [x] T10 改造 `ConversationService`
  - `create(userId, req)` 用 `projectId`，校验项目归属
  - `list(userId, projectId)`
  - `messages` 不变
  - 来源展示（`toResponse`）补充 `documentId`、`filename`
  - 路径：`backend/src/main/java/com/wisread/service/ConversationService.java`

- [x] T11 改造 `VectorIndexingService`（核心）
  - `searchWithContent` 签名：`(userId, projectId, queryEmbedding, limit)`
  - SQL：`WHERE user_id = ? AND document_id IN (SELECT id FROM documents WHERE project_id = ? AND status = 'READY')`
  - 结果集带 `document_id`、通过文档名查询补 `filename`
  - 路径：`backend/src/main/java/com/wisread/service/VectorIndexingService.java`

- [x] T12 改造 `ChatService`
  - `processAsk`：从 `conversation.getProjectId()` 取检索范围
  - 调用 `searchWithContent(userId, projectId, ...)`
  - `ChunkSearchResult` 带 documentId/filename
  - prompt 注入文档名：`[1] 《产品白皮书.pdf》第 6 页 ...`
  - `completeAnswer` 写 `answer_sources.document_id`
  - 路径：`backend/src/main/java/com/wisread/service/ChatService.java`

- [x] T13 改造 `ChunkSearchResult` model
  - 加 `documentId`、`filename` 字段
  - 路径：`backend/src/main/java/com/wisread/model/ChunkSearchResult.java`

---

## 阶段三：后端控制器与 DTO

- [x] T14 新增 Project DTO
  - `CreateProjectRequest(name, description?)`
  - `UpdateProjectRequest(name?, description?)`
  - `ProjectResponse(id, name, description, documentCount, conversationCount, createdAt, updatedAt)`
  - 路径：`backend/src/main/java/com/wisread/dto/`

- [x] T15 新增 `ProjectController`
  - POST/GET/GET{id}/PATCH{id}/DELETE{id}，路由 `/api/v1/projects`
  - 路径：`backend/src/main/java/com/wisread/controller/ProjectController.java`

- [x] T16 改造 `DocumentController`
  - upload 加 `@RequestParam Long projectId`
  - list 加 `@RequestParam Long projectId`
  - 路径：`backend/src/main/java/com/wisread/controller/DocumentController.java`

- [x] T17 改造 `ConversationController`
  - create 用 `CreateConversationRequest(projectId, title?)`
  - list 用 `?projectId=`
  - 路径：`backend/src/main/java/com/wisread/controller/ConversationController.java`

- [x] T18 改造 DTO
  - `CreateConversationRequest`：`documentId` → `projectId`
  - `ConversationResponse`：`documentId` → `projectId`
  - `DocumentResponse`：加 `projectId`
  - `SourceResponse`：加 `documentId`、`filename`
  - 路径：`backend/src/main/java/com/wisread/dto/`

---

## 阶段四：前端类型与 API

- [x] T19 改造 `lib/types.ts`
  - 新增 `Project` 接口
  - `Document` 加 `projectId`
  - `Conversation`：`documentId` → `projectId`
  - `Source` 加 `documentId`、`filename`

- [x] T20 改造 `lib/api.ts`
  - 新增 `listProjects`、`createProject`、`getProject`、`updateProject`、`deleteProject`
  - `listDocuments(projectId)`、`uploadDocument(file, projectId)`
  - `createConversation(projectId, title?)`、`listConversations(projectId)`

---

## 阶段五：前端页面与组件

- [x] T21 新增 `app/projects/page.tsx`（项目列表页）
  - topbar + 项目卡片网格 + 新建项目弹窗
  - 空态引导

- [x] T22 新增 `app/projects/[projectId]/page.tsx`（项目内页）
  - 布局：ProjectSidebar + ChatPanel
  - 加载项目、文档列表、会话列表
  - 会话切换（query param `?conv=`）

- [x] T23 新增 `components/ProjectCard.tsx`

- [x] T24 新增 `components/ProjectCreateDialog.tsx`

- [x] T25 新增 `components/ProjectSidebar.tsx`
  - 文档列表 + 上传按钮
  - 会话列表 + 新建会话
  - 返回项目列表按钮

- [x] T26 新增 `components/Topbar.tsx`
  - 品牌 + 导航 + 新建项目 + 头像/登出
  - 用于项目列表页

- [x] T27 改造 `components/SourceCard.tsx`
  - 显示 `filename · 第 N 页`

- [x] T28 改造 `components/ChatPanel.tsx`
  - 空态文案改为"选择会话或新建会话开始提问"

- [x] T29 改造 `app/page.tsx`
  - 已登录重定向到 `/projects`，未登录到 `/login`

- [x] T30 改造 `app/workspace/page.tsx`
  - 重定向到 `/projects`（已改为纯重定向，路由保留兼容；旧文档级代码及依赖组件已删除）

---

## 阶段六：验证

- [x] T31 后端编译通过（`mvn -o test` BUILD SUCCESS：26 个测试，0 失败 0 错误）
- [x] T32 前端编译通过（`npm run build` 成功，8 条路由生成，无类型错误）
- [ ] T33 手动验证流程：新建项目 → 上传文档 → 新建会话 → 提问 → 来源带文档名
  - 待验证：需启动 Docker 基础设施（Postgres 5433 / MinIO 9002 / Redis 6379）+ 后端 + 前端
- [ ] T34 验证隔离：用户 A 不能访问用户 B 的项目
  - 待验证：同上，需运行时环境（代码层面已按 `findByUserIdAndId` 统一 404 隔离）

---

## 阶段七：首页与布局对齐（原型 home-workspace-prototype）

> 依据原型 `prototypes/home-workspace-prototype/index.html` 的 `view-home`，用户补充需求：登录后应有首页并可跳转项目页；项目列表页要求全屏宽度。

- [x] H1 新增 `app/home/page.tsx`（首页）
  - 问候语 + 日期、4 个数据统计卡（项目/文档/会话/额度）
  - 最近文档面板（跨项目聚合，含状态徽标）+ 最近问答面板（跨项目最近会话）
  - 快捷操作：新建项目 / 打开工作区 / 上传 PDF
  - 无项目时空态引导（文案与项目列表一致）
- [x] H2 改造 `components/Topbar.tsx`
  - 双导航切换：首页 ⇄ 项目，支持 `activeNav` 高亮
  - 品牌区可点击返回首页
- [x] H3 `app/page.tsx` 重定向 `/` → `/home`（登录落地页为首页）
- [x] H4 `/projects` 项目列表改全屏宽度（移除 `max-w-[1180px]` 居中限制，卡片网格自适应填满）
- [x] H5 首页构建验证（`npm run build` 通过：9 条路由生成，含 `/home`，无类型错误）

---

## 已确认决策

1. 前端主题：切换到原型深色主题，本次一并完成视觉对齐
2. 会话路由：`/projects/[projectId]?conv={id}`（query param）
3. 每项目文档上限：5 个
4. 项目描述：支持创建时填写（可选）

> 全部确认，从 T1 开始执行。
