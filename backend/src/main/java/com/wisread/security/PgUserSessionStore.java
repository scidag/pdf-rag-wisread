package com.wisread.security;

import com.wisread.config.WisreadJwtProperties;
import com.wisread.entity.UserSession;
import com.wisread.repository.UserSessionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

import static com.wisread.security.UserSessionStore.hashToken;

/**
 * 会话存储的 PostgreSQL 实现（FR-7，postgres 模式）。
 *
 * <p>保持原有行为：refresh token 以 SHA-256 哈希存于 user_sessions 表，
 * previous_refresh_token_hash 留存上一代用于重放检测。
 * 轮换的绝对到期时间固定为 createdAt + TTL（FR-5，不随刷新后移）。
 *
 * <p>本实现不产生宽限（Previous）结果：R1（PG 阶段）沿用严格重放检测；
 * 宽限与原子轮换由 Redis 实现（{@link RedisUserSessionStore}）在 R2 提供。
 */
@Component
public class PgUserSessionStore implements UserSessionStore {

    private final UserSessionRepository repository;
    private final WisreadJwtProperties jwtProperties;

    public PgUserSessionStore(UserSessionRepository repository, WisreadJwtProperties jwtProperties) {
        this.repository = repository;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public void create(SessionCreate cmd) {
        UserSession session = new UserSession();
        session.setUserId(cmd.userId());
        session.setRefreshTokenHash(hashToken(cmd.refreshToken()));
        session.setDevice(cmd.device());
        session.setIpAddress(cmd.ipAddress());
        session.setExpiresAt(cmd.maxExpireAt());
        repository.insert(session);
    }

    @Override
    public LocateResult locate(String refreshToken) {
        String hash = hashToken(refreshToken);
        Instant now = Instant.now();

        Optional<UserSession> current =
                repository.findByRefreshTokenHashAndExpiresAtAfter(hash, now);
        if (current.isPresent()) {
            return new LocateResult.Found(toSnapshot(current.get()));
        }
        // 上一代哈希命中：判定为重放（R1 阶段无宽限，交由业务层吊销全部会话）
        Optional<UserSession> stale =
                repository.findByPreviousRefreshTokenHashAndExpiresAtAfter(hash, now);
        return stale
                .<LocateResult>map(s -> new LocateResult.Replay(s.getUserId()))
                .orElseGet(LocateResult.NotFound::new);
    }

    @Override
    public RotateOutcome rotate(SessionSnapshot snapshot, String oldToken, String newToken,
                                 String device, String ipAddress) {
        String oldHash = hashToken(oldToken);
        Instant now = Instant.now();

        Optional<UserSession> current =
                repository.findByRefreshTokenHashAndExpiresAtAfter(oldHash, now);
        if (current.isEmpty()) {
            // locate 与 rotate 之间的竞态：另一请求可能已完成轮换，按重放语义处理
            Optional<UserSession> stale =
                    repository.findByPreviousRefreshTokenHashAndExpiresAtAfter(oldHash, now);
            return stale
                    .<RotateOutcome>map(s -> new RotateOutcome.Replay(s.getUserId()))
                    .orElseGet(RotateOutcome.NotFound::new);
        }

        UserSession session = current.get();
        // 轮换：旧哈希归档，写入新哈希，并更新设备/IP
        session.setPreviousRefreshTokenHash(oldHash);
        session.setRefreshTokenHash(hashToken(newToken));
        session.setDevice(device);
        session.setIpAddress(ipAddress);
        // FR-5：绝对到期固定为 createdAt + TTL，不随刷新后移
        session.setExpiresAt(session.getCreatedAt().plus(jwtProperties.getRefreshTokenTtl()));
        repository.updateById(session);
        return new RotateOutcome.Found(toSnapshot(session));
    }

    @Override
    public boolean delete(String refreshToken) {
        return repository.findByRefreshTokenHash(hashToken(refreshToken))
                .map(session -> {
                    repository.deleteById(session.getId());
                    return true;
                })
                .orElse(false);
    }

    @Override
    public void revokeAll(Long userId) {
        repository.deleteByUserId(userId);
    }

    /**
     * 按当前哈希查找会话（含已过期的行），供 redis-migrate 双读回退与迁移清理使用。
     * 返回的快照已按绝对期钳制：maxExpireAt = min(expiresAt, createdAt + TTL)。
     */
    public Optional<SessionSnapshot> findCurrentIncludingExpired(String refreshToken) {
        return repository.findByRefreshTokenHash(hashToken(refreshToken))
                .map(row -> {
                    Instant absolute = row.getCreatedAt().plus(jwtProperties.getRefreshTokenTtl());
                    Instant maxExpireAt = row.getExpiresAt().isBefore(absolute)
                            ? row.getExpiresAt() : absolute;
                    return new SessionSnapshot(row.getUserId(), row.getDevice(),
                            row.getIpAddress(), row.getCreatedAt(), maxExpireAt);
                });
    }

    /** 删除令牌对应的会话行（不校验有效期），迁移成功后的行级清理。 */
    public boolean deleteRowByToken(String refreshToken) {
        return repository.findByRefreshTokenHash(hashToken(refreshToken))
                .map(session -> {
                    repository.deleteById(session.getId());
                    return true;
                })
                .orElse(false);
    }

    private SessionSnapshot toSnapshot(UserSession session) {
        return new SessionSnapshot(session.getUserId(), session.getDevice(),
                session.getIpAddress(), session.getCreatedAt(), session.getExpiresAt());
    }
}
