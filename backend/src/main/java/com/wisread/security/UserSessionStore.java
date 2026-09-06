package com.wisread.security;

import java.time.Instant;

/**
 * 会话存储抽象（FR-7）。
 *
 * <p>实现：{@link PgUserSessionStore}（PostgreSQL）、{@link RedisUserSessionStore}
 * （Redis，含 Lua 原子轮换）与 {@link RoutingUserSessionStore}（按配置路由 + 双读迁移）。
 * 业务层（AuthServiceImpl）只依赖本接口，不感知后端。
 */
public interface UserSessionStore {

    /** 计算令牌的 SHA-256 十六进制哈希（存储层统一使用，避免库表/Redis 保存明文）。 */
    static String hashToken(String token) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 登录/注册时创建会话。 */
    void create(SessionCreate cmd);

    /**
     * 只读定位令牌对应的会话状态。
     * <ul>
     *   <li>Found：当前有效会话</li>
     *   <li>Previous：命中上一代哈希（仅 Redis 模式产生；宽限判定由业务层执行）</li>
     *   <li>Replay：命中上一代哈希（PG 模式产生；业务层应吊销全部会话）</li>
     *   <li>NotFound：无效令牌</li>
     * </ul>
     */
    LocateResult locate(String refreshToken);

    /**
     * 原子轮换：oldToken 的会话替换为 newToken，并留存上一代哈希与宽限上下文。
     * snapshot 来自 {@link #locate(String)} 的 Found 结果（轮换须保持绝对到期不变）。
     */
    RotateOutcome rotate(SessionSnapshot snapshot, String oldToken, String newToken,
                         String device, String ipAddress);

    /** 登出：删除会话（含上一代 prev 链）；返回会话是否存在。 */
    boolean delete(String refreshToken);

    /** 吊销用户全部会话（重放检测 / 改密踢出）。 */
    void revokeAll(Long userId);

    /** 登录/注册创建会话的参数。maxExpireAt = createdAt + 绝对上限（24h）。 */
    record SessionCreate(Long userId, String refreshToken, String device,
                         String ipAddress, Instant createdAt, Instant maxExpireAt) {
    }

    /** 会话快照（不含令牌本体）。 */
    record SessionSnapshot(Long userId, String device, String ipAddress,
                           Instant createdAt, Instant maxExpireAt) {
    }

    /** 上一代哈希命中时的宽限上下文（Redis prev 条目内容）。 */
    record GraceContext(String newHash, String device, String ipAddress, Instant ts, String tokenEnc) {
    }

    sealed interface LocateResult {
        record Found(SessionSnapshot session) implements LocateResult {
        }

        record Previous(GraceContext ctx) implements LocateResult {
        }

        record Replay(Long userId) implements LocateResult {
        }

        record NotFound() implements LocateResult {
        }
    }

    sealed interface RotateOutcome {
        record Found(SessionSnapshot session) implements RotateOutcome {
        }

        record Previous(GraceContext ctx) implements RotateOutcome {
        }

        record Replay(Long userId) implements RotateOutcome {
        }

        record NotFound() implements RotateOutcome {
        }
    }
}
