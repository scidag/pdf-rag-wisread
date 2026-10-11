# 需求文档 · 认证安全修复与 Redis 会话迁移

| 项 | 内容 |
|---|---|
| 文档版本 | v2.1 |
| 创建日期 | 2026-09-06（v2.0 / v2.1 修订同日） |
| 关联文档 | [docs/debug/auth-security-audit.md](debug/auth-security-audit.md)（安全审计报告） |
| 涉及模块 | backend/security、backend/service、backend/config、frontend/lib、deploy |
| 迭代目标 | 修复审计报告全部高危（H1-H2）与中危（M1-M5）问题，登录态收敛为 **1 天绝对有效期**，会话存储迁移至 Redis |

> **v2.2 修订记录**（第三轮，质询"永久 key 如何过期"）：⑬ 用户会话索引取消"永久 key + 每日 SCAN 清理任务"设计，改为 **key TTL = 最后一次登录会话的绝对到期时刻**——得益于 FR-5 的 24h 绝对有效期，TTL 在登录时设置一次即**永不漂移**（轮换只 ZADD 替换成员、不触碰 TTL），key 与用户最后一个会话同生共死、到期自动删除。定时清理任务、SCAN 遍历、机会式清理**全部移除**，零垃圾、零滞留、零维护。
>
> **v2.1 修订记录**（第二轮审查意见 2 条）：⑪ 用户会话索引 ZSET 取消 key TTL（长期活跃用户会无限续期导致 key 永生），改为**永久 key + 空集合即删（ZCARD==0 → DEL）+ 每日定时清理**；⑫ **PREVIOUS 宽限返回设计明确**——服务端以 AES-256-GCM 密文暂存最新一代 refresh token，宽限时解密并**仅通过 Set-Cookie 重发同一最新 token**（不新签发、不破坏轮换、响应体永不含 refresh）。（⑪ 的清理方案在 v2.2 中被 ⑬ 进一步替代）
>
> **v2.0 修订记录**（吸收外部审查意见 10 条）：
> ① 会话迁移由"批量搬迁+删表"改为**双读分阶段迁移**（redis-migrate 模式）；② 用户会话索引 Set → **ZSET**（score=过期时间戳，可清理）；③ Lua 脚本瘦身为**纯状态机**，业务判断收回 Java；④ refresh token 增加**绝对过期**（1 天，到期必须重新登录，不再无限滑动）；⑤ JWT 密钥增加**双密钥轮换机制**；⑥ 限流 Redis 故障增加 **Caffeine 本地兜底**；⑦ 哑哈希改为**配置固定值**（多实例一致）；⑧ 新增**安全攻击测试矩阵**；⑨ 发布策略拆分：R1 低风险先行，R2 Redis **灰度（影子观察）后切换**，不与 R1 合并；⑩ 新增**监控指标与告警**章节。

---

## 一、背景与目标

安全审计发现认证体系存在 2 个高危、5 个中危问题。其中 H1（refresh token 可冒充 access token）攻击成本极低、影响面最大；M4（会话表无索引无清理）随用户增长持续恶化。项目 Redis 已是认证链硬依赖（token 黑名单 fail-closed），将会话迁入 Redis 可解决 M4 的性能与清理问题，并借助 Lua 原子轮换修复 M3 竞态。

**本期目标**：

1. 令牌类型双向隔离，登出后所有已签发令牌立即不可用；
2. 密钥全部外置，支持无感轮换，杜绝硬编码；
3. **登录态有效期收敛为 1 天（绝对过期），到期必须重新登录**（产品要求，同时收窄被盗 refresh token 的利用窗口）；
4. 会话存储迁移 Redis（双读分阶段，不掉线），刷新轮换原子化，支持重放宽限；
5. 限流不可绕过、覆盖刷新接口、Redis 故障有本地兜底；
6. 登录响应时延恒定，消除邮箱枚举侧信道；
7. 补齐账号生命周期最小集（修改密码、忘记密码）。

**不在本期范围**：MFA、社交登录、注销账号完整流程（留待下期）、审计日志平台对接（P3）。

---

## 二、需求总览

| 编号 | 需求 | 优先级 | 对应问题 | 发布 |
|---|---|---|---|---|
| FR-1 | JWT 增加 `typ` claim，access/refresh 双向隔离（含旧 token 兼容策略） | P0 | H1 | R1 |
| FR-2 | 登出同时吊销 access 与 refresh token | P0 | H1 | R1 |
| FR-3 | 认证响应体移除 refreshToken 字段 | P0 | H1 | R1 |
| FR-4 | 密钥治理：外置 + 轮换已泄露密钥 + **双密钥无感轮换机制** | P0 | H2 | R1 |
| FR-5 | **登录态 1 天绝对有效期**，到期需重新登录 | P0 | 审查意见④ + 产品要求 | R1 |
| FR-6 | 登录哑哈希抹平时延（配置固定哈希，多实例一致） | P1 | M2 | R1 |
| FR-7 | Redis 会话存储迁移（**双读分阶段** + ZSET 索引） | P1 | M4 | R2 |
| FR-8 | 刷新轮换 Lua **状态机**原子化 + Java 侧重放宽限 | P1 | M3 | R2 |
| FR-9 | 限流迁移 Redis + **Caffeine 本地兜底**，`/refresh` 纳入限流 | P1 | M1 | R2 |
| FR-10 | 修改密码 + 忘记密码（邮件验证码） | P2 | M5 | R3 |

发布节奏（详见第五章）：**R1**（纯代码、低风险、可独立上线）→ **R2**（Redis，分影子观察/切换/收尾三小步）→ **R3**（生命周期）。

---

## 三、详细需求

### FR-1 JWT 令牌类型双向隔离 【P0 / H1】

**现状**：access 与 refresh token 由同一密钥签发、claims 结构完全相同，`JwtAuthenticationFilter` 接受任何有效签名的 JWT，导致 refresh token 可直接放入 `Authorization: Bearer` 冒充 access token。

**需求**：

1. `JwtService.buildToken()` 增加 claim `typ`：access token 为 `"access"`，refresh token 为 `"refresh"`。
2. `JwtAuthenticationFilter`：解析后校验 `typ == "access"`，否则视为未认证（清空上下文）。
3. `AuthServiceImpl.refresh()`：解析后校验 `typ == "refresh"`，否则返回 401。
4. **旧 token 兼容策略（避免 R1 上线全员掉线）**：R1 期间 refresh 接口接受"签名有效但无 typ claim"的旧 token 并按 refresh 处理——因为 access 侧已强制 `typ == "access"`，无 typ 的令牌无法再作为 access 使用，此兼容不引入安全风险；旧 token 下次刷新即被替换为带 typ 的新 token。R2 发布起强制 `typ == "refresh"`（届时存量会话已全部是 24h 内签发的新格式）。

**验收标准**：

- 新 refresh token 作为 Bearer 请求受保护接口 → 401；
- access token 放入 refresh Cookie 请求 `/refresh` → 401；
- R1 上线后旧 refresh token（无 typ）仍可刷新一次并换得新令牌（不掉线）；
- `JwtServiceTest` 补充上述反向用例。

### FR-2 登出全令牌吊销 【P0 / H1】

**现状**：登出仅拉黑 access token 并删除会话行，refresh token 本身不在黑名单。

**需求**：

1. 登出时将 refresh token 的 jti 也写入 Redis 黑名单（`TokenBlacklistService` 复用，TTL = refresh token 剩余有效期）。
2. `AuthServiceImpl.refresh()` 校验 refresh token 的 jti 未命中黑名单。

**验收标准**：登出后使用原 refresh token 请求 `/refresh` → 401；黑名单 key 随 token 到期自动消失。

### FR-3 认证响应体瘦身 【P0 / H1】

**现状**：`AuthResponse` 在 JSON body 中返回明文 refreshToken，前端从未使用。

**需求**：`AuthResponse` 的 JSON 序列化中移除 `refreshToken` 字段（实现建议：record 保留该字段并标注 `@JsonIgnore`，控制器内部仍需其值写 HttpOnly Cookie，只是不再出现在响应体）；前端 `frontend/lib/types.ts` 同步删除类型定义。**明确规则：refresh token 一律且仅通过 Set-Cookie 下发，任何接口的响应体都不得包含它**（FR-8 宽限路径同样遵守）。

**验收标准**：登录/注册/刷新（含宽限）接口响应体仅含 `{accessToken, expiresIn, user}`；前端构建通过，认证流程回归正常。

### FR-4 密钥治理与轮换机制 【P0 / H2 + 审查意见⑤】

**现状**：`application-prod.yml` 硬编码真实 DashScope API Key 与生产 JWT secret；仅单一 `JWT_SECRET`，更换即全体掉线，无轮换手段。

**需求**：

1. **立即轮换**已暴露的 DashScope API Key 与 JWT secret（视为已泄露）。
2. **双密钥轮换机制**：配置 `wisread.jwt.secret-current`（签发+校验）与 `wisread.jwt.secret-previous`（仅校验，可空）。`JwtService` 构造两把密钥；解析时先试 current，失败再试 previous；**签发只用 current**。轮换流程：`PREVIOUS = 旧 CURRENT`、`CURRENT = 新值` → 滚动发布 → 旧 token（≤24h）自然消亡后清空 PREVIOUS。
3. `application-prod.yml` 密钥仅保留 `${ENV_VAR}` 占位、无默认值；`docker-compose.yml` 启动前校验 `JWT_SECRET_CURRENT`、`SESSION_CIPHER_KEY`、`POSTGRES_PASSWORD`、`MINIO_ROOT_PASSWORD` 为空或等于示例弱值时**拒绝启动**。
4. `deploy/.env.example` 注释生成方式（`openssl rand -base64 48`）。
5. **新增会话密文密钥**（服务 FR-8）：`SESSION_CIPHER_KEY`（32 字节，AES-256-GCM），用于加密暂存的最新一代 refresh token。密钥更新只影响更新时点的在途宽限场景（旧密文 5 分钟内自然失效），无需专门迁移流程。

**验收标准**：仅配置 PREVIOUS（无 CURRENT）启动失败；用 PREVIOUS 签发的旧 token 校验通过但新签发均为 CURRENT 签名；仓库与工作区 grep 不到真实密钥；弱默认值启动被拒。

### FR-5 登录态 1 天绝对有效期 【P0 / 产品要求 + 审查意见④】

**现状**：refresh token 7 天**滑动续期**——每次刷新重置为"当前时间 + 7 天"，活跃用户可无限期保持登录；被盗 refresh token 的利用窗口同样无上限。

**需求**：

1. **有效期语义变更**：refresh token 从"7 天滑动"改为"**自登录起 24 小时绝对过期**"（`wisread.jwt.refresh-token-ttl: 24h`，语义为会话绝对上限）。24 小时内可多次刷新换新 access token，但**轮换不延长绝对到期时间**；到期后 refresh 返回 401，前端清除本地态并引导重新登录。
2. 会话记录（PG 阶段用 `created_at`，Redis 阶段用 `createdAt/maxExpireAt` 字段）承载绝对上限：轮换时 `expiresAt = createdAt + 24h`（固定值，不再随刷新后移）。
3. `AuthController` 下发的 `wisread_refresh` Cookie `Max-Age` 由 `7*24*3600` 改为 `24*3600`。
4. 前端 `auth-store.ts`：refresh 失败（401）时清空内存 token；各页面守卫统一跳转登录页并提示"登录已过期，请重新登录"（区分于"未登录"）。
5. R1 上线时存量旧会话自然收敛：旧 7 天会话在下次刷新时被绝对上限截断为 `createdAt + 24h`，超期者重新登录。

**验收标准**：

- 登录后第 23 小时刷新成功、第 25 小时刷新 → 401 且需重新登录；
- 连续活跃（每 10 分钟刷新一次）也不能跨越 24 小时边界；
- Cookie Max-Age 为 86400；
- 前端过期后统一跳登录页并有明确提示。

### FR-6 登录哑哈希抹平时延 【P1 / M2 + 审查意见⑦】

**现状**：邮箱不存在时立即抛 401（~1ms），存在时执行 BCrypt 比对（~50-100ms），时间差可枚举邮箱。

**需求**：用户不存在分支对**配置固定的哑哈希**执行一次 `passwordEncoder.matches(raw, dummyHash)` 后再抛 401。哑哈希来自 `wisread.auth.dummy-password-hash` 配置（一个预生成的 BCrypt 串，非机密、可提交入库，保证多实例一致且 cost 与真实密码哈希一致）。

**验收标准**：两种失败路径响应时间差 < 5ms（100 次取中位数）；多实例哑哈希一致。

### FR-7 Redis 会话存储迁移（双读分阶段）【P1 / M4 + 审查意见①②】

**现状**：refresh 会话存 PG `user_sessions`，哈希列无索引（每次刷新/登出全表扫描）、过期行无清理（表无限增长）。

**迁移原则**：**不做"批量搬迁 + 删表"**（存在"Redis 写失败但 PG 已删"的窗口，导致全量掉线）。改为**双读懒迁移**，由配置驱动阶段切换：

```yaml
wisread:
  session-store:
    mode: postgres        # postgres | redis-migrate | redis
```

**阶段一 `redis-migrate`（双读）**：

- **写路径**（登录/注册）：仅写 Redis；
- **读路径**（刷新/登出）：先查 Redis → 未命中再查 PG → PG 命中则**先写 Redis（含 maxExpireAt = min(PG expiresAt, created_at + 24h)）**，成功后**删除该单行 PG 记录**（行级、写后删，Redis 写失败则 PG 行保留，无数据丢失窗口）→ PG 也未命中按无效 token 处理；
- PG 中已超 24h 绝对期的旧行：导入时判为过期直接删除（等效旧会话失效，用户重新登录）。

**阶段二 `redis`（观察稳定后切换）**：读路径只查 Redis。切换条件：连续 7 天 PG 命中率 ≈ 0 且 `auth_refresh_success_total` 无异常下跌。

**阶段三（下个迭代收尾）**：删除双读代码；`user_sessions` 表截断后保留结构作审计留存（或按运维决策归档删除）。

**Redis 数据结构**（含审查意见②⑪）：

```
# 会话主键：哈希直查；TTL = min(24h, maxExpireAt - now)，由 Java 计算后传入
# 只存哈希与元数据，不存任何 token 明文/密文
wisread:session:hash:<sha256(refreshToken)>
  → Hash{userId, prevHash, device, ip, createdAt, maxExpireAt}

# 重放检测条目：旧哈希 → 轮换上下文 + 最新 token 密文
# TTL = min(会话剩余生命周期, 24h)，与所属会话同生共死（不只是宽限窗口，
# 否则超过 5 分钟的旧 token 会退化为 NOT_FOUND 而漏掉重放吊销）
wisread:session:prev:<sha256>
  → Hash{newHash, device, ip, ts, latestTokenEnc}

# 用户会话索引：ZSET，member=refreshHash，score=该会话 maxExpireAt
# key TTL = 该用户最后一次登录会话的绝对到期时刻（登录/注册时 EXPIRE 设置，见下"索引生命周期"）
wisread:session:user:<userId>
  → ZSET{member: hash, score: maxExpireAt}   TTL = max(maxExpireAt)
```

**索引生命周期**（审查意见⑪⑬，TTL 对齐绝对到期，免维护）：

原 v2.1 采用"永久 key + 机会式清理 + 每日 SCAN 任务"，清理滞后最长 24h 且常驻定时任务。v2.2 改为 **key TTL 与会话绝对到期对齐**：

- **登录/注册**（新建会话）：`ZADD`（score=maxExpireAt）后执行 `EXPIRE key = maxExpireAt - now`——TTL 是**固定截止时刻**而非"now+24h"，只有新登录才会推后，推后幅度 = 新会话自身生命期（≤24h）；
- **轮换**（FR-8 Lua）：`ZADD` 替换成员（score 不变）且**不执行 EXPIRE**——Redis 语义保证对已存在 key 的 ZADD 不影响 TTL，因此刷新不会给 key 续命。审查意见⑪担心的"Day100 仍存在"场景：Day100 存在是**应该的**（用户有活跃会话）；Day101 会话死亡，key 同步自动消失；
- **登出单会话**：`ZREM` 对应成员（key TTL 自然兜底，无需额外处理）；
- **吊销全部会话**（重放/改密踢出）：按成员删除 hash key 后直接 `DEL` 索引 key；
- 过期成员（其他设备的会话先到期）至多滞留至 key 自身到期，数量 ≤ 用户设备数，可忽略；轮换 Lua 中可顺带 `ZREMRANGEBYSCORE 0 now` 作整洁性优化（可选）。

**内存上界**（回答"用户多了会不会很多"）：key 数量 = **当前持有存活会话的用户数**（滚动窗口 ≤ 24h），与注册用户总量、历史登录次数**完全无关**——索引只存"现在时"，不存历史。单个 ZSET（≤5 设备）约 400-600 字节：10 万日活 ≈ 50MB 量级；达到百万级并发会话时 Redis 本身需要分片，索引不构成额外瓶颈。对比被否决的两个方案：依赖 key TTL 随写入续期（活跃用户 key 永生，审查意见⑪）与永久 key + 定时任务（v2.1，清理滞后 + 运维负担），本方案零任务、零滞留、零垃圾。

**实现要求**：

1. 新建 `UserSessionStore` 组件封装全部会话 Redis 操作（含按模式路由 PG/Redis 的双读逻辑），`AuthServiceImpl` 不再直接依赖 `UserSessionRepository`（R2 期间保留 Repository 以支持双读）。
2. `deploy/docker-compose.yml` Redis 服务追加：`appendonly yes`、`maxmemory-policy noeviction`。
3. 定时任务（每日一次）：按上述"索引清理策略"执行 SCAN 分批清理（ZREMRANGEBYSCORE + 空集合 DEL）。
4. `latestTokenEnc` 的加解密由 `UserSessionStore` 内部的 `TokenCipher` 组件负责（AES-256-GCM，密钥 `SESSION_CIPHER_KEY`），`AuthServiceImpl` 只接触明文 token，不感知加密细节。

**验收标准**：

- `redis-migrate` 模式下：刷新/登出优先命中 Redis；PG 残留会话可被正常读取并迁入 Redis；
- 模拟"Redis 写入失败"：PG 行不删除，下次刷新仍可恢复（**不允许出现两边都没有的会话**）；
- 切 `redis` 模式后刷新/登出不再产生 user_sessions SQL；
- Redis（AOF 开启）重启后会话存活；
- 并发 100 用户同时刷新无错误。

### FR-8 刷新轮换原子化与重放宽限 【P1 / M3 + 审查意见③】

**现状**：轮换为"查 → 改 → 写"非原子，多标签页并发刷新时后到者命中 previous 哈希被误判重放，吊销全部会话。

**设计原则**（审查意见③）：**Lua 只做原子状态转换，不做业务判断**。device/IP/时间比较放在 Java 侧，避免脚本复杂化（尤其移动网络 IP 漂移场景易误杀，且脚本缺陷难调试）。

**Lua 脚本职责**（单脚本原子执行，只做状态转换、不做业务判断，返回状态码 + 数据）：

```
输入: KEYS = [hash:<cur>, hash:<new>, prev:<cur>, user:<uid>]
      ARGV = [会话字段(userId, device, ip, createdAt, maxExpireAt), ttlSeconds, latestTokenEnc]
逻辑:
  EXISTS hash:<cur>  → 读旧会话 → 写 hash:<new>（TTL=ARGV）
                      → 写 prev:<cur> = {newHash, device, ip, ts, latestTokenEnc}（TTL=会话剩余）
                      → ZADD user:<uid> score=maxExpireAt（不执行 EXPIRE，保持原 key TTL）
                      → DEL hash:<cur> → 返回 NORMAL
  否则 EXISTS prev:<cur> → 返回 PREVIOUS（附带 prev 全部字段：newHash/device/ip/ts/latestTokenEnc，
                           不做任何判断、不写任何数据）
  否则 → 返回 NOT_FOUND
```

**Java 侧宽限决策**（仅 PREVIOUS 状态时执行）：

1. `ts` 距今超过宽限窗口（默认 5 分钟）→ 判定**重放**；
2. 窗口内则比较指纹：`device`（User-Agent）必须一致；IP 不一致**仅记 WARN 日志不拦截**（移动网络切 WiFi/4G 属正常场景），`wisread.auth.refresh-grace-strict-ip: true` 可切换为严格模式；
3. 判定重放 → 吊销该用户全部会话（`ZREMRANGEBYSCORE 0 now` 清理后删除全部 hash key）并返回 401 `refresh token reuse detected`，`auth_refresh_replay_total +1`；
4. 判定宽限 → 走下方"PREVIOUS 返回设计"。

**PREVIOUS 宽限返回设计**（审查意见⑫，必须明确"返回哪个 refresh"）：

问题背景：并发双 tab（A 先到完成轮换得到 newToken，B 后到命中 PREVIOUS）——B 若只拿 access token，其 Cookie 仍是旧值，宽限期一过 B 的下次刷新就会升级为重放；B 若签发全新 refresh token，则又制造一代轮换（A 手里的 newToken 退化为 prev，形成链式宽限，污染指标且链上任何一环超窗即误杀）。

**方案：B 重发 A 拿到的同一个最新 token**。前提是服务端必须能在轮换后还原"最新一代 refresh token"——本设计不落明文，而是在轮换时以 AES-256-GCM 加密暂存（`prev` 条目中的 `latestTokenEnc` 字段，密钥 `SESSION_CIPHER_KEY`）。宽限流程：

```
PREVIOUS + 宽限通过
  → 解密 latestTokenEnc 得到 newToken（即 A 收到的那一代）
  → Set-Cookie: wisread_refresh = newToken（B 的 Cookie 收敛到与 A 相同）
  → 签发新 access token（基于会话 user）
  → 响应体: {accessToken, expiresIn, user}   ← 不含 refresh（与 FR-3 规则一致）
```

设计效果：

- **不新签发、不产生新轮换代**——rotate 语义完整保留，`auth_refresh_grace_total` 纯粹度量"并发竞态"，不会被链式宽限污染成稳态噪音；
- 双 tab Cookie 收敛为**同一个值**，后续请求全部走 NORMAL，宽限只是竞态窗口内的一次性纠偏；
- 密文暂存窗口 = prev 条目 TTL（≤24h），Redis 泄露单独发生时无 `SESSION_CIPHER_KEY` 无法还原 token，安全 posture 与"只存哈希"基本等价；
- 加解密失败（密钥轮换等边缘场景）：按重放处理（fail-closed），指标 `redis_session_error_total +1`。

**背景补充**：同浏览器多 tab 共享同一 Cookie jar，A 的刷新响应落地后 B 的后续请求天然读到新 Cookie；PREVIOUS 竞态实际只发生在"两个请求都在 A 响应落地前发出"的亚秒级窗口。宽限窗口（5 分钟）远大于该窗口，是刻意留出的冗余。

**验收标准**：

- 两并发请求携带同一有效 refresh token：至少一个 NORMAL 成功，另一个走 PREVIOUS → 宽限成功或干净 401，**绝不触发全端吊销**；
- 并发宽限场景：后到者响应的 `Set-Cookie` 值与先到者**完全一致**（同一最新 token），且响应体不含 refresh；
- 携带超过宽限窗口的旧 token → 401 且全部会话吊销；
- 同 device 换 IP 的旧 token 宽限期内 → 放行（仅 WARN 日志）；
- 不同 device 的旧 token → 401 且全部会话吊销；
- Redis dump 中不存在 refresh token 明文（`latestTokenEnc` 为密文，无密钥不可还原）。

### FR-9 限流迁移 Redis + 本地兜底 【P1 / M1 + 审查意见⑥】

**现状**：`AuthRateLimitFilter` 以 `getRemoteAddr()` 限流（`forward-headers-strategy: framework` 使 X-Forwarded-For 可伪造绕过）；内存计数多实例失效且 Deque 永不清理；`/refresh` 未限流。

**需求**：

1. 限流计数迁 Redis（固定窗口）：`wisread:rl:auth:<ip>`（60s / 10 次）、`wisread:rl:refresh:<ip>`（60s / 30 次）。
2. IP 解析：新增 `wisread.trust-proxy`（默认 `false`）——false 取 TCP 对端地址，true 取 `X-Forwarded-For` 首个 IP（仅部署于可信反向代理后开启）。
3. **二级兜底**（审查意见⑥）：Redis 限流调用失败时，降级到**本地 Caffeine 固定窗口限流**（单实例阈值同配置），并打 WARN + `redis_rl_fallback_total +1`。认证接口不允许完全裸奔。
4. 删除内存 Map/Deque 实现。

**验收标准**：同 IP 第 11 次 login → 429；`/refresh` 超 30 次/分 → 429；trust-proxy=false 时伪造 X-Forwarded-For 不影响计数主体；Redis 停机时本地兜底生效（同 IP 超阈仍 429），日志出现 WARN。

### FR-10 账号生命周期最小集 【P2 / M5】

**现状**：无修改密码、无忘记密码，忘记密码即永久丢失账号。

**需求**（邮件依赖 `SPRING_MAIL_*` SMTP 配置，一期支持验证码）：

1. **修改密码** `PATCH /api/v1/users/me/password`：`{oldPassword, newPassword}`（新密码同注册策略 + 常见口令 deny-list）；校验旧密码 → 更新哈希 → 吊销该用户除当前会话外全部会话（遍历 ZSET 索引）→ 当前 access token 进黑名单，引导重新登录。
2. **忘记密码**：
   - `POST /api/v1/auth/forgot-password` `{email}`：无论邮箱是否存在均返回 202（防枚举，哑处理抹平时延）；存在时生成 6 位数字验证码，`wisread:pwd:code:<email>` TTL 10 分钟，发送邮件（发送频率限制：同邮箱 1 分钟 1 次、1 小时 5 次）。
   - `POST /api/v1/auth/reset-password` `{email, code, newPassword}`：校验验证码（错 5 次作废）→ 更新密码 → 吊销该用户全部会话。
3. 前端 AuthScreen 增加"忘记密码？"入口与两步表单页；设置菜单增加修改密码入口；验证码邮件为中文模板（含验证码与 10 分钟有效期提示）。
4. 独立开关 `wisread.auth.lifecycle-enabled`（默认 true），故障时可关闭不影响登录主流程。

**验收标准**：修改密码后其他设备 refresh 失败；忘记密码全流程（收码→重置→旧会话全部失效→新密码登录）端到端可用；枚举测试（不存在邮箱）响应码与时延同存在邮箱一致（202，时延差 < 5ms）。

---

## 四、配置项汇总（新增/变更）

```yaml
wisread:
  jwt:
    secret-current: ${JWT_SECRET_CURRENT}          # 签发 + 校验（必填，无默认值）
    secret-previous: ${JWT_SECRET_PREVIOUS:}      # 仅校验，轮换过渡期使用
    access-token-ttl: 15m
    refresh-token-ttl: 24h                         # 语义变更：绝对有效期（原 7d 滑动）
  auth:
    dummy-password-hash: <预生成固定 BCrypt 串>     # 多实例一致，非机密
    refresh-grace-window: 5m                       # PREVIOUS 宽限判定窗口（ts 距今）
    refresh-grace-strict-ip: false                 # true 时 IP 不一致即判重放
    legacy-typ-accepted: true                      # R1 兼容旧 token，R2 起置 false
    lifecycle-enabled: true                        # FR-10 开关
  session:
    cipher-key: ${SESSION_CIPHER_KEY}              # AES-256-GCM，加密暂存最新 refresh token（必填，32 字节）
  session-store:
    mode: postgres                                 # postgres | redis-migrate | redis
  trust-proxy: false                               # true 时信任 X-Forwarded-For
  ratelimit:
    auth-per-minute: 10
    refresh-per-minute: 30
```

---

## 五、发布计划（分三批，不合并发布）

> 审查意见⑨：R2 属大改（Redis 会话 + Lua + 迁移 + 限流），不与 R1 合并。R1 通过旧 token 兼容（FR-1.4）与绝对期自然截断（FR-5.5）已消除掉线风险，可与 R2 安全解耦。

| 发布 | 内容 | 风险 | 掉线影响 |
|---|---|---|---|
| **R1** | FR-1 ~ FR-6（纯代码逻辑，PG 存储不变） | 低 | 兼容旧 token，无掉线；超 24h 的旧会话自然过期属预期 |
| **R2-a** | FR-7/8/9 代码上线，`mode=redis-migrate`（双读，PG 兜底） | 中 | 无（双读保底） |
| **R2-b** | 观察 3-7 天（刷新成功率、Redis 错误率、PG 命中率）后切 `mode=redis` | 中 | 无 |
| **R2-c** | 下个迭代删除双读代码、清理 PG 表、`legacy-typ-accepted=false` | 低 | 无 |
| **R3** | FR-10 | 低 | 无 |

**R2 观察期指标与准入**：`auth_refresh_success_total` 增速无下跌；`redis_session_error_total` ≈ 0；PG 双读命中率持续走低趋 0。任一异常 → 保持 `redis-migrate` 排查，不切换。

---

## 六、安全测试矩阵（发布前必须全绿）

### JWT 攻击测试

| 用例 | 构造方式 | 期望 |
|---|---|---|
| 修改 typ | 合法 refresh token 改 typ=access 后重签（或反之） | 401 |
| 删除 typ | 去掉 typ claim 重签 | 401（access 侧）/ R2 起 refresh 也 401 |
| alg:none | 无签名伪造 token | 401（jjwt 拒绝） |
| 过期 token | 等待/回拨 exp | 401 |
| 错误签名 | 用另一把密钥签发 | 401（current/previous 均不匹配） |
| refresh 冒充 access | 新 refresh token 放 Bearer 头 | 401 |
| access 冒充 refresh | access token 放 refresh Cookie | 401 |
| previous 密钥令牌 | 用 PREVIOUS 签发的合法 token | 校验通过（轮换期预期内） |

### Refresh 攻击测试

| 用例 | 期望 |
|---|---|
| 双标签页并发刷新同一 token | 一个 NORMAL + 一个 PREVIOUS 宽限，无全端吊销；**两者 Set-Cookie 收敛为同一最新 token** |
| 超宽限窗口的旧 token | 401 + 全部会话吊销 |
| 旧 token + 不同 device | 401 + 全部会话吊销 |
| 旧 token + 同 device + 换 IP | 宽限放行 + WARN 日志 |
| 篡改 Cookie 值 | 401 |
| 登出后旧 refresh token 再刷新 | 401（jti 黑名单命中） |
| 第 25 小时刷新 | 401（绝对过期） |
| 宽限后再次刷新（Cookie 已收敛） | 走 NORMAL 正常轮换，不产生第二次宽限 |
| 解密失败（密钥不匹配的 latestTokenEnc） | 按重放处理（fail-closed）+ 错误计数 |

### Redis 故障测试

| 用例 | 期望 |
|---|---|
| `FLUSHALL` | 行为明确：会话丢失 → 用户重新登录（可接受）；黑名单丢失 → 已登出 access token 在剩余 ≤15min 内复活（**已知风险，文档化**，运维侧禁用 FLUSHALL 命令名） |
| Redis 重启（AOF on） | 会话恢复，刷新成功率不掉 |
| 网络抖动 | 黑名单 fail-closed（401）；限流降级本地兜底；双读回落 PG |
| `redis-migrate` 模式 Redis 写失败 | PG 行保留，会话不丢 |
| ZSET 索引 TTL 对齐 | 用户最后一个会话到期后 key 自动消失（无任务介入）；活跃期间轮换不延长 key TTL；多设备过期成员至多滞留至 key 到期 |

---

## 七、监控与告警（R2 起必须上线）

**指标**（Micrometer / Prometheus，`management.endpoints` 已暴露 prometheus）：

| 指标 | 类型 | 用途 |
|---|---|---|
| `auth_login_success_total` / `auth_login_fail_total{reason}` | Counter | 登录健康度、失败原因分布（invalid_credentials / disabled / rate_limited） |
| `auth_refresh_success_total` | Counter | 刷新成功率（迁移观察期核心指标） |
| `auth_refresh_replay_total` | Counter | 重放检测次数——**区分真攻击与客户端 bug 的关键** |
| `auth_refresh_grace_total` | Counter | 宽限触发次数（持续偏高 = 前端并发逻辑缺陷） |
| `auth_refresh_latency_seconds` | Timer (P99) | 性能回归（目标 < 50ms） |
| `redis_session_error_total` | Counter | 会话存储错误（双读回落 PG 时也计数并带 mode 标签） |
| `redis_rl_fallback_total` | Counter | 限流本地兜底触发（> 0 持续即 Redis 异常信号） |
| `session_store_mode` | Gauge | 当前迁移阶段（0=postgres / 1=migrate / 2=redis） |

**告警规则**：

| 条件 | 级别 |
|---|---|
| `auth_refresh_success_total` 5 分钟环比下跌 > 30% | Page（迁移期可能全员掉线） |
| `redis_session_error_total` 5 分钟 > 0 | Warn → 持续 15 分钟 Page |
| `auth_refresh_replay_total` 5 分钟增量 > 阈值（如 10） | 安全告警，人工核查（真攻击 vs 客户端 bug） |
| `redis_rl_fallback_total` 10 分钟 > 100 | Warn（Redis 抖动） |

---

## 八、非功能需求

| 类别 | 要求 |
|---|---|
| 兼容性 | R1 旧 token（无 typ）可刷新一次完成迁移；旧 7 天会话被 24h 绝对期截断；R2 双读保底不丢会话 |
| 性能 | 刷新接口 P99 < 50ms（Redis O(1)）；access 鉴权新增 typ 校验为纯内存判断（≤1ms） |
| 可用性 | 分级故障策略：黑名单/会话存储 fail-closed（401）；限流 fail-open + 本地兜底；双读回落 PG |
| 安全 | 新端点纳入 traceId/异常/限流体系；密码策略 8~64 位 + deny-list；哑哈希多实例一致 |
| 测试 | 单测覆盖 FR-1/2/4/5/6/8/9/10 核心分支；集成测试：并发刷新、登出全失效、限流触发、双读回落、Redis 重启恢复；安全测试矩阵全绿 |

---

## 九、风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| Redis 重启/FLUSHALL 丢会话 | compose 强制 AOF + noeviction；FLUSHALL 命令改名禁用 | 影响为重新登录，无数据损坏 |
| Lua 脚本缺陷 | 脚本仅做状态转换（无业务判断）；宽限分支兜底；灰度期监控刷新成功率 | `mode` 切回 postgres（双读代码保留一个迭代） |
| 双密钥解析增加鉴权耗时 | 仅 current 失败后才试 previous；正常流量零额外开销 | 清空 secret-previous 即恢复单密钥 |
| 1 天绝对期引发用户投诉 | 产品预期行为；过期提示语明确"登录已过期，请重新登录" | 调大 `refresh-token-ttl` 即放宽（配置化） |
| 宽限窗口被针对性利用 | 窗口仅 5 分钟且要求 device 一致；`auth_refresh_grace_total` 可观测 | `refresh-grace-window: 0` 关闭宽限 |
| 验证码邮件服务不可用 | 独立开关 lifecycle-enabled；发送失败 500 + 告警，不影响登录主流程 | 关闭开关 |

---

## 十、验收清单（发布前逐项勾选）

- [ ] refresh token 作 Bearer → 401；access 作 refresh → 401（FR-1）
- [ ] R1 上线后旧 token 可完成最后一次刷新（不掉线）（FR-1.4）
- [ ] 登出后原 refresh token 调 /refresh → 401（FR-2）
- [ ] 响应体无 refreshToken 字段（FR-3）
- [ ] 仅配置 previous 密钥启动失败；双密钥校验/单密钥签发符合预期（FR-4）
- [ ] 仓库/工作区无真实密钥；弱默认值拒绝启动（FR-4）
- [ ] 第 23 小时刷新成功、第 25 小时 → 401 需重新登录；Cookie Max-Age=86400（FR-5）
- [ ] 邮箱存在/不存在登录失败时延差 < 5ms；多实例哑哈希一致（FR-6）
- [ ] redis-migrate 模式：PG 残留会话可迁移；模拟 Redis 写失败不丢会话（FR-7）
- [ ] 切 redis 模式后无 user_sessions SQL；AOF 重启会话存活（FR-7）
- [ ] 并发刷新绝不触发全端吊销；超窗/异 device 重放正常吊销（FR-8）
- [ ] 并发宽限的双响应 Set-Cookie 为同一最新 token；响应体无 refresh；Redis 无 token 明文（FR-8）
- [ ] ZSET 索引空 key 被删除；长期活跃用户 key 无过期成员堆积（FR-7）
- [ ] 同 IP 11 次 login → 429；/refresh 超限 → 429；Redis 停机本地兜底生效（FR-9）
- [ ] 修改密码后他端掉线；忘记密码全流程可用；防枚举一致（FR-10）
- [ ] 安全测试矩阵（第六章）全部用例通过
- [ ] 监控指标与告警规则已在 Prometheus 验证可用（R2 起）
- [ ] 后端全量测试通过；前端 build 通过；冒烟（登录→刷新→登出→重登→24h 过期提示）通过
