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

```bash
cd backend
mvn spring-boot:run
```

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
