# Wisread MVP Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Initialize the Wisread monorepo and deliver a running Spring Boot backend with PostgreSQL/pgvector, MinIO, Flyway schema, and email + password registration/login with JWT access token and refresh token.

**Architecture:** Monorepo with `backend/`, `frontend/`, `deploy/`, and `docs/`. The backend uses Spring Boot 3.4.5, Spring Security, Spring AI Alibaba, Spring Data JPA, Flyway, PostgreSQL + pgvector, and MinIO. Auth uses JWT access token (15 min) plus refresh token (7 days) stored as SHA-256 in `user_sessions`.

**Tech Stack:** Java 21, Spring Boot 3.4.5, Spring AI Alibaba 1.0.0-M6.1, PostgreSQL 16 + pgvector, MinIO, Flyway, Next.js (Phase 2+), Docker Compose.

---

## Task 1: Initialize Repository and Infrastructure

**Files:**
- Create: `.gitignore`
- Create: `deploy/docker-compose.yml`
- Create: `deploy/.env.example`

- [ ] **Step 1: Initialize git and create feature branch**

Run:
```bash
git init
git checkout -b feat/wisread-mvp-phase1
```

Expected: repository initialized on `feat/wisread-mvp-phase1`.

- [ ] **Step 2: Add root `.gitignore`**

```gitignore
# Java
backend/target/
*.class

# Node
frontend/node_modules/
frontend/.next/
frontend/dist/

# IDE
.idea/
.vscode/
*.iml

# Env
.env
deploy/.env

# OS
.DS_Store
Thumbs.db
```

- [ ] **Step 3: Add Docker Compose infrastructure**

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: wisread-postgres
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-wisread}
      POSTGRES_USER: ${POSTGRES_USER:-wisread}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-wisread123}
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U wisread"]
      interval: 5s
      timeout: 5s
      retries: 10

  minio:
    image: minio/minio:latest
    container_name: wisread-minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-wisread}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-wisread123}
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio_data:/data

volumes:
  postgres_data:
  minio_data:
```

- [ ] **Step 4: Add environment example**

```bash
POSTGRES_DB=wisread
POSTGRES_USER=wisread
POSTGRES_PASSWORD=wisread123
MINIO_ROOT_USER=wisread
MINIO_ROOT_PASSWORD=wisread123
MINIO_ENDPOINT=http://localhost:9000
MINIO_BUCKET=wisread-documents
```

- [ ] **Step 5: Start infrastructure and verify**

Run:
```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env.example up -d
docker compose -f deploy/docker-compose.yml ps
```

Expected: `postgres` and `minio` containers in `running` state.

## Task 2: Backend Skeleton

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/wisread/WisreadApplication.java`
- Create: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Create Maven project**

`backend/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.5</version>
        <relativePath/>
    </parent>

    <groupId>com.wisread</groupId>
    <artifactId>wisread-backend</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>wisread-backend</name>

    <properties>
        <java.version>21</java.version>
        <spring-ai-alibaba.version>1.0.0-M6.1</spring-ai-alibaba.version>
        <jjwt.version>0.12.6</jjwt.version>
        <minio.version>8.5.17</minio.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-starter</artifactId>
            <version>${spring-ai-alibaba.version}</version>
        </dependency>
        <dependency>
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
            <version>${minio.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>${jjwt.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <repositories>
        <repository>
            <id>spring-milestones</id>
            <url>https://repo.spring.io/milestone</url>
        </repository>
    </repositories>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Add application main class**

`backend/src/main/java/com/wisread/WisreadApplication.java`:

```java
package com.wisread;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WisreadApplication {

    public static void main(String[] args) {
        SpringApplication.run(WisreadApplication.class, args);
    }
}
```

- [ ] **Step 3: Add application configuration**

`backend/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: wisread-backend
  datasource:
    url: ${POSTGRES_URL:jdbc:postgresql://localhost:5432/wisread}
    username: ${POSTGRES_USER:wisread}
    password: ${POSTGRES_PASSWORD:wisread123}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:}
      chat:
        options:
          model: ${WISREAD_CHAT_MODEL:qwen-plus}
      embedding:
        options:
          model: ${WISREAD_EMBEDDING_MODEL:text-embedding-v3}

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health,info

wisread:
  jwt:
    secret: ${JWT_SECRET:wisread-dev-secret-change-me-please-32bytes}
    access-token-ttl: 15m
    refresh-token-ttl: 7d
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ROOT_USER:wisread}
    secret-key: ${MINIO_ROOT_PASSWORD:wisread123}
    bucket: ${MINIO_BUCKET:wisread-documents}
```

## Task 3: Flyway Schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init_schema.sql`

- [ ] **Step 1: Create schema migration**

The migration must create all V2 tables from the spec: `users`, `user_sessions`, `documents`, `document_chunks`, `conversations`, `messages`, `answer_sources`, `document_jobs`, `usage_logs`, `feedback`, plus pgvector extension and indexes.

- [ ] **Step 2: Verify migration runs**

Run:
```bash
cd backend
mvn -q -DskipTests package
```

Expected: Spring Boot context loads and Flyway reports no pending migrations.

## Task 4: Auth Domain

**Files:**
- Create: `backend/src/main/java/com/wisread/entity/User.java`
- Create: `backend/src/main/java/com/wisread/entity/UserSession.java`
- Create: `backend/src/main/java/com/wisread/repository/UserRepository.java`
- Create: `backend/src/main/java/com/wisread/repository/UserSessionRepository.java`

- [ ] **Step 1: Create User entity**

Fields: `id`, `username`, `email`, `passwordHash`, `avatarUrl`, `status`, `createdAt`, `updatedAt`.

- [ ] **Step 2: Create UserSession entity**

Fields: `id`, `userId`, `refreshTokenHash`, `device`, `ipAddress`, `expiresAt`, `createdAt`.

- [ ] **Step 3: Create repositories**

`UserRepository` exposes `existsByEmail`, `existsByUsername`, `findByEmail`.
`UserSessionRepository` exposes `findByRefreshTokenHashAndExpiresAtAfter`, `deleteByUserId`.

## Task 5: JWT and Security

**Files:**
- Create: `backend/src/main/java/com/wisread/config/WisreadJwtProperties.java`
- Create: `backend/src/main/java/com/wisread/security/JwtService.java`
- Create: `backend/src/main/java/com/wisread/security/JwtAuthenticationFilter.java`
- Create: `backend/src/main/java/com/wisread/config/SecurityConfig.java`

- [ ] **Step 1: Create JWT properties**

`@ConfigurationProperties(prefix = "wisread.jwt")` with `secret`, `accessTokenTtl`, `refreshTokenTtl`.

- [ ] **Step 2: Create JwtService**

Methods: `createAccessToken(Long userId, String username)`, `createRefreshToken(Long userId, String username)`, `parseUserId(String token)`. Sign with HS256 from the configured secret.

- [ ] **Step 3: Create JwtAuthenticationFilter**

Reads `Authorization: Bearer <token>`, validates token, and puts `userId` into the security context.

- [ ] **Step 4: Configure Security**

Stateless session, permit register/login/refresh/health, require auth for everything else, register the filter, return JSON 401 for missing/invalid tokens.

## Task 6: Auth API

**Files:**
- Create: `backend/src/main/java/com/wisread/dto/RegisterRequest.java`
- Create: `backend/src/main/java/com/wisread/dto/LoginRequest.java`
- Create: `backend/src/main/java/com/wisread/dto/AuthResponse.java`
- Create: `backend/src/main/java/com/wisread/dto/UserResponse.java`
- Create: `backend/src/main/java/com/wisread/service/AuthService.java`
- Create: `backend/src/main/java/com/wisread/controller/AuthController.java`
- Create: `backend/src/main/java/com/wisread/controller/UserController.java`
- Create: `backend/src/main/java/com/wisread/controller/HealthController.java`
- Create: `backend/src/main/java/com/wisread/exception/ApiException.java`
- Create: `backend/src/main/java/com/wisread/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Create request/response DTOs**

Register: `username`, `email`, `password`.
Login: `email`, `password`.
AuthResponse: `accessToken`, `expiresIn`, `user`.
UserResponse: `id`, `username`, `email`.

- [ ] **Step 2: Implement AuthService**

Register rejects duplicate email/username, hashes password with BCrypt, saves user.
Login validates credentials, creates `UserSession` with refresh token hash, returns tokens.
Refresh rotates refresh token and returns new access token.
Logout deletes the session by refresh token hash.

- [ ] **Step 3: Implement controllers**

`POST /api/v1/auth/register`
`POST /api/v1/auth/login`
`POST /api/v1/auth/refresh`
`POST /api/v1/auth/logout`
`GET /api/v1/users/me`
`GET /api/v1/health`

## Task 7: MinIO Client

**Files:**
- Create: `backend/src/main/java/com/wisread/config/WisreadMinioProperties.java`
- Create: `backend/src/main/java/com/wisread/config/MinioConfig.java`
- Create: `backend/src/main/java/com/wisread/service/MinioStorageService.java`

- [ ] **Step 1: Add MinIO properties and beans**

`@ConfigurationProperties(prefix = "wisread.minio")`.

- [ ] **Step 2: Create storage service**

Methods: `putObject(String key, byte[] bytes, String contentType)`, `deleteObject(String key)`. Ensure bucket exists at startup.

## Task 8: Tests and Verification

**Files:**
- Create: `backend/src/test/java/com/wisread/security/JwtServiceTest.java`
- Create: `backend/src/test/java/com/wisread/service/AuthServiceTest.java`

- [ ] **Step 1: Write JWT unit tests**

Token creation and parsing round-trip; expired token throws.

- [ ] **Step 2: Write AuthService tests**

Register success, duplicate email rejected, login success, wrong password rejected.

- [ ] **Step 3: Run full verification**

Run:
```bash
cd backend
mvn test
```

Expected: all tests pass.

## Self-Review Notes

- Phase 1 does not include PDF upload, RAG, or frontend; those are Phase 2/3 plans.
- The `spring-ai-alibaba-starter` dependency is included now so the RAG phase can reuse the same Maven setup.
- Auth endpoints are versioned under `/api/v1`.
