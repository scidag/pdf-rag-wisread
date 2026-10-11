# 实施 Spec · 认证安全修复与 Redis 会话迁移（R1 + R2）

> 依据：[需求文档-认证安全修复与Redis迁移.md](需求文档-认证安全修复与Redis迁移.md) v2.2
> 范围：R1（FR-1~FR-6，安全止血）+ R2（FR-7~FR-9，Redis 迁移）。FR-10（账号生命周期，R3）不在本 Spec，待 SMTP 决策后另立。

---

## 1. 类与组件设计

### 1.1 配置类（`config/`）

| 类 | 前缀 | 字段 | 默认值 |
|---|---|---|---|
| `WisreadJwtProperties`（改造） | `wisread.jwt` | `secretCurrent`(必填)、`secretPrevious`(可空)、`accessTokenTtl`=15m、`refreshTokenTtl`=**24h**（语义变更：绝对上限） | — |
| `WisreadAuthProperties`（新增） | `wisread.auth` | `dummyPasswordHash`、`refreshGraceWindow`=5m、`refreshGraceStrictIp`=false、`legacyTypAccepted`=true | 哑哈希未配置时启动时随机生成 |
| `WisreadSessionProperties`（新增） | `wisread.session` | `cipherKey`（Base64，32 字节，必填 fail-fast） | — |
| `WisreadSessionStoreProperties`（新增） | `wisread.session-store` | `mode`: `postgres` \| `redis-migrate` \| `redis` | `postgres` |
| `WisreadRateLimitProperties`（新增） | `wisread.ratelimit` | `authPerMinute`=10、`refreshPerMinute`=30 | — |
| `WisreadTrustProxyProperties`（新增） | `wisread` | `trustProxy`=false | — |

### 1.2 安全组件（`security/`）

| 类 | 职责 |
|---|---|
| `JwtService`（改造） | ① `typ` claim（access/refresh）双向隔离；② 双密钥：current 签发+校验、previous 仅校验（解析失败回退）；③ `parseClaims` 公开，供过滤器单次解析；④ `parseType(token)` |
| `JwtAuthenticationFilter`（改造） | 单次 `parseClaims`；`typ != "access"` → 清空上下文（FR-1） |
| `TokenBlacklistService`（不动） | jti 黑名单，refresh 登出复用（FR-2） |
| `TokenCipher`（新增） | AES-256-GCM（12 字节随机 IV，Base64(iv+cipher)）；密文暂存最新 refresh token（FR-8 宽限）；构造时校验密钥 32 字节 fail-fast |
| `UserSessionStore`（新增接口） | 会话存储抽象，见 §2 |
| `PgUserSessionStore`（新增） | PG 实现（包 `UserSessionRepository`） |
| `RedisUserSessionStore`（新增） | Redis 实现（Lua 轮换 + Hash/ZSET 结构） |
| `RoutingUserSessionStore`（新增，@Primary） | 按 `mode` 路由；`redis-migrate` 含 PG 双读回退与迁移 |
| `AuthRateLimiter`（新增） | Redis INCR+EXPIRE 固定窗口；Redis 故障 → Caffeine 本地兜底（FR-9） |
| `AuthRateLimitFilter`（改造） | 覆盖 login/register/refresh；trust-proxy IP 解析；替换内存 Deque |
| `AuthMetrics`（新增） | Micrometer 计数器：login.success/fail、refresh.success/replay/grace、redis.session.error、redis.rl.fallback；registry 缺席时退化为 SimpleMeterRegistry |

### 1.3 服务与控制器

| 类 | 变更 |
|---|---|
| `AuthServiceImpl`（重写刷新逻辑） | 依赖改为 `UserSessionStore` + `TokenBlacklistService` + `TokenCipher` + `AuthMetrics` + `WisreadAuthProperties`；流程见 §3 |
| `AuthController` | ① Cookie Max-Age 取 `refreshTokenTtl`（86400）；② logout 同时黑名单 refresh jti（FR-2）；③ `readRefreshCookie` 不再抛异常（改返回 null，refresh 端点显式 401） |
| `AuthResponse` | `refreshToken` 标注 `@JsonIgnore`（控制器内部仍用，响应体不含，FR-3） |
| `GlobalExceptionHandler` | 不动 |

---

## 2. UserSessionStore 接口定义

```java
public interface UserSessionStore {
    void create(SessionCreate cmd);                       // 登录/注册
    LocateResult locate(String refreshToken);             // 只读定位
    RotateOutcome rotate(SessionSnapshot snapshot, String oldToken,
                         String newToken, String device, String ip);  // 原子轮换
    boolean delete(String refreshToken);                  // 登出（含清理 prev 链）
    void revokeAll(Long userId);                           // 全端吊销
}

record SessionCreate(Long userId, String refreshToken, String device,
                     String ipAddress, Instant createdAt, Instant maxExpireAt) {}
record SessionSnapshot(Long userId, String device, String ipAddress,
                       Instant createdAt, Instant maxExpireAt) {}
record GraceContext(String newHash, String device, String ipAddress, Instant ts, String tokenEnc) {}

sealed interface LocateResult {
    record Found(SessionSnapshot session) implements LocateResult {}
    record Previous(GraceContext ctx) implements LocateResult {}   // 仅 Redis 模式
    record Replay(Long userId) implements LocateResult {}           // 命中上一代（PG）
    record NotFound() implements LocateResult {}
}
// RotateOutcome 同构：Found / Previous / Replay / NotFound
```

**路由规则**（`RoutingUserSessionStore`）：

| 操作 | postgres | redis-migrate | redis |
|---|---|---|---|
| create | PG | Redis | Redis |
| locate | PG | Redis → 未命中且 PG 命中 → 迁入 Redis（保留 createdAt/maxExpireAt，先写后删行）→ Found | Redis |
| rotate | PG | Redis（locate 已完成迁移） | Redis |
| delete | PG | Redis → 未命中再 PG | Redis |
| revokeAll | PG | **双后端** | Redis |

**PG 迁移时的绝对期钳制**：`maxExpireAt = min(PG.expiresAt, createdAt + 24h)`；已过期 → 删 PG 行，返回 NotFound。

---

## 3. 关键时序

### 3.1 刷新主流程（AuthServiceImpl.refresh）

```
1. 空值检查 → 401
2. jwtService.parseType(token)            → 签名/过期异常 → 401 invalid
   typ != "refresh" 且 !(legacyTypAccepted && typ==null) → 401        [FR-1]
3. tokenBlacklistService.isBlacklisted(token) → 401                    [FR-2]
4. locate(token):
     Replay(userId)   → revokeAll(userId); 401 reuse-detected; metrics.replay++
     NotFound         → 401 invalid
     Previous(ctx)    → handleGrace(ctx)                              [仅 Redis]
     Found(snapshot)  → 继续
5. 加载 user（不存在/禁用 → 401）
6. newRefresh = jwt.createRefreshToken(user)
7. rotate(snapshot, token, newRefresh, device, ip):
     Replay(userId)   → revokeAll; 401 reuse
     NotFound         → 401 invalid
     Previous(ctx)    → handleGrace(ctx)（并发竞态）
     Found            → AuthResponse(access, newRefresh, ...)          [cookie 下发]
```

### 3.2 handleGrace（FR-8 宽限）

```
1. tokenEnc 为空/解密失败 → 401 invalid（fail-closed）; metrics.sessionError++
2. tokenN = cipher.decrypt(ctx.tokenEnc)
3. locate(tokenN) != Found → 401 invalid（目标会话已死：登出/吊销/再轮换）
4. 重放判定：ts < now-graceWindow || device 不一致 || (strictIp && ip 不一致)
     → revokeAll(userId); 401 reuse-detected; metrics.replay++
5. metrics.grace++; 加载 user → 签发 access
   → AuthResponse(access, tokenN, ...)  ← Cookie 重发同一最新 token（不新签发）
```

### 3.3 Redis 数据结构与 Lua 轮换

```
wisread:session:hash:<sha256>   Hash{userId, device, ip, createdAt, maxExpireAt, prevHash}
                                TTL = maxExpireAt - now（固定截止，轮换重设为同值）
wisread:session:prev:<sha256>  Hash{newHash, device, ip, ts, tokenEnc}
                                TTL = 会话剩余（与所属会话同生共死）
wisread:session:user:<uid>     ZSET{member=hash, score=maxExpireAt}
                                key TTL = max(maxExpireAt)（登录设置；轮换 ZADD 不触碰 TTL）
```

rotate.lua（KEYS=[hashOld, hashNew, prevOld, userUid]；ARGV=[sessTtl, prevTtl, zscore, newHash, userId, device, ip, createdAt, maxExpireAt, oldHash, ts, tokenEnc]）：

```lua
if EXISTS hashOld == 0 then
    if EXISTS prevOld == 1 then return {'PREVIOUS', unpack(HGETALL prevOld)} end
    return {'NOT_FOUND'}
end
HSET hashNew {userId, device, ip, createdAt, maxExpireAt, prevHash=oldHash}
EXPIRE hashNew sessTtl
HSET prevOld {newHash, device, ip, ts, tokenEnc}
EXPIRE prevOld prevTtl
ZADD userUid zscore newHash
ZREM userUid oldHash
DEL hashOld
return {'NORMAL'}
```

- delete(token)：读 hash → DEL hash + DEL prev:<prevHash> + ZREM（登出连 prev 链一起清）
- revokeAll(uid)：ZRANGE → 逐成员 DEL hash + DEL prev:<prevHash> → DEL user key

---

## 4. 其余实现要点

- **FR-4**：`application-prod.yml` 全部密钥改 `${ENV_VAR:}` 无默认值；`application.yml` 保留 dev 占位（JwtService 延续弱密钥 fail-fast）；`deploy/.env.example` 补 `JWT_SECRET_CURRENT/SESSION_CIPHER_KEY` 说明。
- **FR-5**：PG 轮换 `expiresAt = createdAt + ttl`（固定，不随刷新后移）；注册/登录时 `expiresAt = now + ttl`。
- **FR-6**：用户不存在分支执行 `matches(raw, dummyHash)` 再抛 401；dummyHash 取配置，未配置则在构造时对随机串 encode 一次。
- **FR-9**：`application.yml` 移除 `server.forward-headers-strategy: framework`（XFF 伪造入口，见审计 M1），IP 解析由过滤器按 trust-proxy 自行控制。
- **pom.xml**：新增 `com.github.ben-manes.caffeine:caffeine`（Boot 管版本）。
- **前端**：`types.ts` 的 `AuthResponse` 删除 `refreshToken` 字段；`auth-store.ts` 无需改动（未使用该字段）。

## 5. 测试计划

| 测试 | 覆盖 |
|---|---|
| `JwtServiceTest`（重写） | typ 双向隔离（refresh 作 Bearer 解析拒绝 / access typ 断言）、双密钥（previous 签发可校验、签发只用 current）、typ 篡改 |
| `TokenCipherTest`（新） | 加解密往返、错误密钥失败、密文含随机 IV（同明文两次密文不同） |
| `PgUserSessionStoreTest`（新） | locate/rotate/replay、绝对期钳制 |
| `AuthServiceImplTest`（重写 AuthServiceTest） | typ 拒绝、黑名单拒绝、哑哈希路径、绝对期 25h 401、宽限放行/重放吊销、宽限 Cookie 收敛 |
| `AuthControllerTest`（新） | logout 黑名单 refresh、响应体无 refreshToken（@JsonIgnore） |
| `AuthRateLimitFilterTest`（重写） | 429 阈值、trust-proxy 开关、Redis 故障走 Caffeine 兜底 |
| 前端 | `npm run build` |

## 6. 验收对照

按需求文档第十章验收清单逐项执行；重点：refresh 作 Bearer → 401；登出后旧 refresh → 401；第 25 小时 → 401；并发刷新不触发全端吊销；双响应 Set-Cookie 收敛为同一最新 token；Redis 无 token 明文。
