# Wisread 部署文档

## 1. Docker Compose

基础设施包含 PostgreSQL + pgvector 和 MinIO：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env.example up -d
```

查看状态：

```bash
docker compose -f deploy/docker-compose.yml ps
```

停止：

```bash
docker compose -f deploy/docker-compose.yml down
```

## 2. 端口说明

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| PostgreSQL | 5433 | 本机 5432 可能被原生 PostgreSQL 占用，故映射 5433 |
| MinIO API | 9002 | 本机 9000 可能被原生 MinIO 占用，故映射 9002 |
| MinIO Console | 9003 | MinIO Web 管理台 |
| 后端 | 8080 | Spring Boot |
| 前端 | 3000 | Next.js |

## 3. 环境变量

复制示例并修改：

```bash
cp deploy/.env.example deploy/.env
```

关键变量：

| 变量 | 说明 |
| --- | --- |
| `POSTGRES_DB / POSTGRES_USER / POSTGRES_PASSWORD` | PostgreSQL 配置 |
| `MINIO_ROOT_USER / MINIO_ROOT_PASSWORD` | MinIO 账号 |
| `MINIO_ENDPOINT` | 后端访问 MinIO 的地址 |
| `JWT_SECRET` | JWT 签名密钥，生产必须更换 |
| `DASHSCOPE_API_KEY` | 阿里云百炼 API Key |
| `WISREAD_EMBEDDING_MOCK_ENABLED` | 本地 Mock Embedding，生产设为 false |
| `WISREAD_CHAT_MOCK_ENABLED` | 本地 Mock Chat，生产设为 false |

## 4. 数据库迁移

Flyway 在 Spring Boot 启动时自动执行 `backend/src/main/resources/db/migration` 下的脚本。

查看迁移版本：

```bash
docker exec wisread-postgres psql -U wisread -d wisread -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

## 5. 生产注意事项

- 设置强随机 `JWT_SECRET`，不要使用默认值
- 配置真实 `DASHSCOPE_API_KEY`，关闭本地 Mock
- 修改后端 CORS `allowedOrigins` 为实际前端域名
- 登录/refresh Cookie 在 HTTPS 下自动带 `Secure`
- MinIO bucket 会在后端启动时自动创建
- 删除文档会级联删除 chunks、messages、answer_sources，并删除 MinIO 对象

## 6. 常见问题

### 登录返回 500

检查 `user_sessions.device` 列是否已经应用 `V2` 迁移；浏览器 User-Agent 较长，旧版本 100 字符会溢出。

### 上传后一直 PROCESSING

检查 MinIO endpoint 是否可达，以及 bucket 是否已创建。

### 问答返回“文档中没有找到相关信息”

- 检查问题是否与文档内容相关
- 本地 Mock 使用确定性哈希向量，语义检索能力有限，接入 DashScope 后效果更好
