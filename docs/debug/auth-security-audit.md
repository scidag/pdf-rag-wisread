# 「智阅」用户登录/注册与权限验证体系 · 流程分析与安全审计报告

> 生成日期：2026-09-06
> 审计范围：后端 Spring Security/JWT 认证链、会话管理、RBAC、前端登录注册与会话恢复
> 技术栈：Spring Boot 3 + Spring Security（JWT 无状态）｜ Next.js App Router ｜ PostgreSQL + Redis

---

## 一、系统架构与完整操作流程

### 1.1 注册流程 `POST /api/v1/auth/register`

```
前端 AuthScreen.tsx (无预校验)
  → auth-store.register() POST JSON {username, email, password}
  → AuthRateLimitFilter (仅按 IP 限流)
  → @Valid 校验 (RegisterRequest: username≤50, email≤100, 密码 8~64)
  → AuthServiceImpl.register():
      ① existsByUsername / existsByEmail 查重 → 冲突 409
      ② BCrypt 加密密码 → 落库 (role 默认 USER, status=1)
      ③ issueTokens(): 签发 access(15m) + refresh(7d) JWT
      ④ refresh token 的 SHA-256 哈希写入 user_sessions 表
  → 响应 201: {accessToken, refreshToken(明文!), expiresIn, user}
  → Set-Cookie: wisread_refresh=<JWT>; HttpOnly; SameSite=Lax; Path=/api/v1/auth/refresh
  → 前端仅将 accessToken 存入 JS 模块内存变量，直接跳转 /home
```

关键文件：

- `backend/src/main/java/com/wisread/controller/AuthController.java`（端点与 Cookie 下发）
- `backend/src/main/java/com/wisread/service/impl/AuthServiceImpl.java`（注册/登录/刷新/登出核心逻辑）
- `backend/src/main/java/com/wisread/dto/RegisterRequest.java`（入参校验）
- `frontend/components/AuthScreen.tsx`、`frontend/lib/auth-store.ts`（前端表单与提交）

### 1.2 登录流程 `POST /api/v1/auth/login`

1. DTO 校验（仅非空 + 邮箱格式）→ 限流过滤；
2. `AuthServiceImpl.login()`：按邮箱查用户 → BCrypt 比对密码 → 校验 `status==1`（封禁能力）；
3. 凭据错误统一返回 `invalid credentials`（401），防账号枚举；
4. 记录 User-Agent 与 RemoteAddr 到会话 → 签发令牌对 + HttpOnly Cookie。

### 1.3 请求鉴权链（每个受保护请求）

```
TraceIdFilter → AuthRateLimitFilter (仅 login/register 计数) → JwtAuthenticationFilter:
  ① Redis hasKey(jti) 检查黑名单（fail-closed：Redis 挂 → 视为未认证）
  ② 签名/过期校验（jjwt verifyWith，HMAC 算法固定，无 alg 混淆风险）
  ③ 每请求查库 findById(userId)，校验 status
  ④ 用数据库中的实时 role 构造 ROLE_ 权限写入 SecurityContext
→ SecurityConfig URL 级授权：/api/v1/admin/** 需 ADMIN，其余需 authenticated
→ 业务层 ownership 校验（如 ProjectServiceImpl.findOwnedProject:
   user_id + id + 未删除三条件查询，越权返回 404）
```

关键文件：

- `backend/src/main/java/com/wisread/config/SecurityConfig.java`（过滤器链与授权规则）
- `backend/src/main/java/com/wisread/security/JwtAuthenticationFilter.java`
- `backend/src/main/java/com/wisread/security/JwtService.java`
- `backend/src/main/java/com/wisread/security/TokenBlacklistService.java`（Redis 黑名单，按 jti）
- `backend/src/main/java/com/wisread/security/AuthRateLimitFilter.java`（滑动窗口限流）
- `backend/src/main/java/com/wisread/controller/UserController.java`、`AdminController.java`

### 1.4 会话管理（令牌刷新与登出）

- **刷新轮换**（`AuthServiceImpl.refresh()`）：采用 *rotate + 重放检测*——
  - 按当前哈希查未过期会话；命中则把旧哈希归档到 `previous_refresh_token_hash`、写入新哈希、更新 device/IP/expiredAt，签发新令牌对；
  - 未命中时用 previous 哈希排查旧令牌重放，命中 → **吊销该用户全部会话** 并返回 401 `refresh token reuse detected`。
- **登出**：access token 按 jti 进 Redis 黑名单（TTL=剩余有效期）+ 删除会话行 + 当前/旧路径双 Cookie 清除。
- **前端**（`frontend/lib/api.ts`）：收到 401 自动调用 `restoreSession()` 单飞刷新后重试一次；accessToken 仅存内存，refresh 依赖 HttpOnly Cookie（`credentials: "include"`）。

### 1.5 做得好的地方（整体高于同类 MVP 水准）

| 设计 | 评价 |
|---|---|
| BCrypt 存储密码；refresh token 只存 SHA-256 哈希（不存明文） | ✅ 符合 OWASP ASVS |
| JWT 密钥未配置/为默认值时启动即失败（fail-fast） | ✅ JwtService 构造器 |
| Refresh 轮换 + 重放检测 + 全会话吊销 | ✅ 超出 MVP 常见水准 |
| 资源所有权用 `user_id + id` 复合查询，越权返回 404 | ✅ 无 IDOR |
| 角色权限每请求从 DB 实时读取（改角色/封号即时生效） | ✅ |
| 黑名单 fail-closed、登出 fail-closed（Redis 不可用拒绝而非放行） | ✅ 安全优先于可用性 |
| CORS 凭据模式 + 具体来源非 `*`；JWT 固定 HMAC 算法 | ✅ |
| 全局异常兜底不泄露内部细节；注册/登录区分错误码（409/401） | ✅ |

---

## 二、问题清单（按严重程度排序）

### 🔴 H1（高危）Refresh Token 可直接充当 Access Token，且登出后仍有效

**证据**：`JwtService.createAccessToken/createRefreshToken` 由同一密钥签发、claims 结构完全相同（同 subject/roles/jti），**无 `typ`/`aud` 区分**；`JwtAuthenticationFilter.parseUserId(token)` 接受任何能通过签名的 JWT。

**攻击链**：`AuthResponse` 在 JSON body 中返回明文 refreshToken。一旦泄露（日志/代理/XSS/错误上报），攻击者可将其放入 `Authorization: Bearer` 头**直接作为 7 天有效的 access token**。登出只拉黑 access token 的 jti、删除会话行——**被盗 refresh token 本身不在黑名单**，受害者登出后它仍可继续当 access token 用到自然过期。

**建议**：
1. refresh token 增加 `typ: "refresh"` claim；`JwtAuthenticationFilter` 拒绝含此 claim 的 token，`/refresh` 接口拒绝不含此 claim 的 token（双向隔离）。
2. 登出时同时将 refresh token 的 jti 拉黑（TTL=其剩余有效期）。
3. 响应体中移除 `refreshToken` 字段（前端从未使用，纯暴露面）。

### 🔴 H2（高危）生产配置文件硬编码真实密钥

`backend/src/main/resources/application-prod.yml`（已被 .gitignore，未入库）硬编码了真实 DashScope API Key 与生产 JWT secret；`deploy/.env.example` 使用 `wisread123` 弱默认密码（Postgres/MinIO）。

**建议**：密钥全部迁移至环境变量并**轮换已暴露的 Key**；docker-compose 启动时校验弱默认值并拒绝启动。

### 🟠 M1（中危）限流可被 X-Forwarded-For 伪造绕过，且仅单机内存态

`AuthRateLimitFilter` 以 `request.getRemoteAddr()` 限流，而 `application.yml` 配置了 `forward-headers-strategy: framework`——Spring 的 ForwardedHeaderFilter 会使 `X-Forwarded-For` 成为 RemoteAddr，**客户端可伪造该头，每次请求换假 IP → 暴力破解限流形同虚设**。此外：

- 计数器为 `ConcurrentHashMap` 内存态，多实例部署各自计数（阈值实际 ×N）；
- 每个 IP 的 Deque 永不清理（仅该 IP 下次请求时裁剪），海量伪造 IP → 内存泄漏（自身又是 DoS 面）；
- 仅限流 login/register，`/refresh` 未限流（可无限刷，每次产生 DB+Redis 查询与轮换写）。

**建议**：迁 Redis 滑动窗口/令牌桶；在反向代理（nginx）层做限流；仅信任可信代理边界的真实 IP。

### 🟠 M2（中危）登录时序侧信道 → 邮箱枚举

`AuthServiceImpl.login()`：邮箱不存在时立即抛 401（~1ms），存在时执行 BCrypt 比对（~50-100ms）。响应时间差稳定可测，与"消息统一防枚举"的初衷矛盾。

**建议**：用户不存在时对哑哈希执行一次 `passwordEncoder.matches(raw, DUMMY_BCRYPT_HASH)` 再返回，抹平时延。另：注册接口 409 区分 "username/email already exists" 本身即枚举通道（行业常见权衡，配合限流可接受，建议统一提示并依赖邮箱验证兜底）。

### 🟠 M3（中危）并发刷新竞态 → "误判重放 → 全端登出"

轮换非原子：两个标签页（各自独立 JS 运行时，`refreshPromise` 单飞只在单标签内有效）同时携带同一 cookie 请求 `/refresh`，后到者命中 previous 哈希 → 吊销该用户**所有**会话并踢出全部设备。真实高频 UX 事故。

**建议**：previous 窗口内匹配时，若 device/IP 指纹一致且时间差 < N 秒则幂等返回新 token（或宽限一次），仅指纹不符时才判重放。

### 🟠 M4（中危）user_sessions 表：查询无索引 + 无过期清理

`V1__init_schema.sql`：`refresh_token_hash` 与 `previous_refresh_token_hash` **均无索引**（仅 user_id 有），每次刷新/登出为全表顺序扫描；过期会话**无清理任务**，表无限增长 → 刷新接口性能随历史用户数线性恶化。

**建议**：两列建索引；定时任务清理 `expires_at < now()` 的行。

### 🟠 M5（中危）账号生命周期流程整体缺失（合规硬伤）

全库检索确认：**无修改密码、无忘记密码/重置、无邮箱验证、无注销账号、无 MFA** 端点。忘记密码即永久丢失账号；无法删除账号违反 PIPL/GDPR 删除权；开放注册 + 无邮箱验证 → 可用他人邮箱批量注册（配合 M2 可实施骚扰）。

### 🟡 低危/加固项（L1-L8）

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| L1 | Cookie `Secure` 依赖 `request.isSecure()`，代理未传 X-Forwarded-Proto 时降级为非 Secure | AuthController | 生产无条件 `setSecure(true)` |
| L2 | 注释写 SameSite=Strict 实际 Lax；logout 存在 CSRF（诱导登出，低危） | AuthController | 修正注释；可加自定义头校验 |
| L3 | 无 HSTS/X-Frame-Options/CSP 等安全响应头 | 全局 | 网关或 SecurityFilterChain headers 配置 |
| L4 | BCrypt cost 默认 10；密码策略仅长度 8~64，无常见口令 deny-list | AuthServiceImpl | cost 提至 12；按 NIST 800-63B 加 deny-list |
| L5 | 用户名无字符集约束（可含 HTML/同形字），仅靠 React 转义兜底 | RegisterRequest | 加 `@Pattern` 白名单 |
| L6 | 注册"查重后插入"竞态：并发同邮箱注册后者触发唯一约束 → 500 而非 409 | AuthServiceImpl | 捕获 DataIntegrityViolationException 转 409 |
| L7 | TraceIdFilter 将客户端 `X-Trace-Id` 未清洗直接回显响应头 | TraceIdFilter | 校验 `[0-9a-zA-Z-]{1,64}` 再透传 |
| L8 | 无认证事件审计日志（登录成功/失败/重放吊销均无日志，device/ip 只存库不告警） | AuthServiceImpl | 对 401/重放/封禁打结构化 WARN 日志并接入告警 |

---

## 三、性能瓶颈

1. **每请求 2 次跨网络调用**：Redis `hasKey` + DB `findById`（JwtAuthenticationFilter）。可对 user(status,role) 做 30-60s 的 Caffeine 短缓存（牺牲封禁即时性）。
2. **刷新接口 O(n) 全表扫描**：refresh_token_hash 无索引 + 会话表无清理（见 M4）。
3. **前端首页扇出**：N 个项目发起 2N 个文档/会话请求（`home/page.tsx` 的 `Promise.all` 嵌套），项目多时首屏放大明显；可参照后端 `countStatsByUserId` 的聚合思路。

---

## 四、用户体验问题

| 问题 | 位置 |
|---|---|
| 表单零预校验：邮箱格式/密码长度都要等后端往返才发现 | AuthScreen.tsx handleSubmit |
| 后端英文错误消息（"username already exists"、"too many requests"）原样展示给中文界面用户 | GlobalExceptionHandler + AuthScreen |
| 无"忘记密码"入口（对应 M5） | AuthScreen 表单区 |
| 社交登录按钮永远禁用展示（"即将支持"），占视觉主位 | AuthScreen SocialButton |
| Terms/Privacy 链接为 `href="#"` 死链 | AuthScreen 底部 |
| 会话过期后 apiFetch 抛错但多数页面不跳登录页，仅部分页面挂载时守卫（projects/home），体验不一致 | 各页面分散守卫 |
| 429 限流无冷却倒计时/友好提示 | AuthRateLimitFilter 响应 |

---

## 五、改进路线图

| 优先级 | 事项 | 对应问题 |
|---|---|---|
| P0（立即） | JWT 加 `typ` claim 双向隔离 + 登出拉黑 refresh jti + 响应体移除 refreshToken | H1 |
| P0 | 轮换并外置 DashScope Key/JWT secret；compose 校验弱默认值 | H2 |
| P1 | 限流迁 Redis + 代理层限流 + 只信可信代理 IP；`/refresh` 纳入限流 | M1/M3 |
| P1 | refresh_token_hash/previous 建索引 + 过期会话清理任务 | M4 |
| P1 | 登录哑哈希抹平时延 | M2 |
| P2 | 忘记密码 + 修改密码 + 注销账号 + 邮箱验证（分期） | M5 |
| P2 | 刷新宽限窗口防多标签页误杀；错误消息中文化 + 客户端预校验 | M3/UX |
| P3 | 安全响应头、BCrypt cost、审计日志、性能缓存 | L1-L8 |

> 总评：核心密码学与会话设计（轮换、重放检测、fail-closed、所有权校验）明显高于同类 MVP 水准；主要短板集中在 **token 类型未隔离（H1）**、**限流可绕过（M1）**、**账号生命周期缺失（M5）** 三处。H1 改动量小（约 20 行）而收益最大，建议立即修复。
