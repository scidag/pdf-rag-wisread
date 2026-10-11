# 智阅启动指南

## 1. 启动基础设施

在项目根目录执行：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env.example up -d
```

确认 PostgreSQL 和 MinIO 都已启动：

```bash
docker compose -f deploy/docker-compose.yml ps
```

## 2. 启动后端

本地开发可直接启动：`application-prod.yml`（已 gitignore，不进仓库）内置了随机生成的本地开发密钥。

```bash
cd backend
chcp 65001   # Windows 控制台按 UTF-8 显示日志，避免中文乱码
mvn spring-boot:run
```

生产部署时用环境变量覆盖本地默认值（设置后文件内默认值失效）：
`JWT_SECRET_CURRENT`（JWT 密钥，≥32 字节）、`SESSION_CIPHER_KEY`（会话密文密钥，Base64 的 32 字节）。

可选：`WISREAD_SESSION_STORE_MODE`（`postgres` | `redis-migrate` | `redis`，默认 `postgres`）、
`WISREAD_TRUST_PROXY`（部署于可信反向代理后设 `true`）。

后端地址：`http://localhost:8080`

## 3. 启动前端

```bash
cd frontend
npm install   # 仅首次需要
npm run dev
```

前端地址：`http://localhost:3000`

## 停止

后端和前端终端里按 `Ctrl+C`，再执行：

```bash
docker compose -f deploy/docker-compose.yml down
```
