package com.wisread.security;

import com.wisread.config.WisreadSessionStoreProperties;
import com.wisread.config.WisreadSessionStoreProperties.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * 会话存储路由门面（FR-7 双读分阶段迁移）。
 *
 * <p>按 {@code wisread.session-store.mode} 路由：
 * <ul>
 *   <li>postgres：全部读写走 PG（R1 行为）</li>
 *   <li>redis-migrate：写只写 Redis；读 Redis 未命中回落 PG，
 *       命中则迁移该行到 Redis（先写后删，行级、失败不删源行，无数据丢失窗口）</li>
 *   <li>redis：只读写 Redis（PG 仅保留 revokeAll 兜底清理）</li>
 * </ul>
 */
@Component
@Primary
public class RoutingUserSessionStore implements UserSessionStore {

    private static final Logger log = LoggerFactory.getLogger(RoutingUserSessionStore.class);

    private final PgUserSessionStore pg;
    private final RedisUserSessionStore redis;
    private final WisreadSessionStoreProperties properties;

    public RoutingUserSessionStore(PgUserSessionStore pg, RedisUserSessionStore redis,
                                   WisreadSessionStoreProperties properties) {
        this.pg = pg;
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void create(SessionCreate cmd) {
        if (properties.getMode() == Mode.POSTGRES) {
            pg.create(cmd);
        } else {
            redis.create(cmd);
        }
    }

    @Override
    public LocateResult locate(String refreshToken) {
        if (properties.getMode() == Mode.POSTGRES) {
            return pg.locate(refreshToken);
        }
        LocateResult result = redis.locate(refreshToken);
        if (!(result instanceof LocateResult.NotFound)) {
            return result;
        }
        if (properties.getMode() == Mode.REDIS) {
            return result;
        }
        // redis-migrate：双读回退，PG 命中则迁移后返回
        return migrateFromPg(refreshToken);
    }

    @Override
    public RotateOutcome rotate(SessionSnapshot snapshot, String oldToken, String newToken,
                                 String device, String ipAddress) {
        if (properties.getMode() == Mode.POSTGRES) {
            return pg.rotate(snapshot, oldToken, newToken, device, ipAddress);
        }
        // redis-migrate：locate 已把 PG 会话迁入 Redis，轮换只走 Redis（原子 Lua）
        return redis.rotate(snapshot, oldToken, newToken, device, ipAddress);
    }

    @Override
    public boolean delete(String refreshToken) {
        if (properties.getMode() == Mode.POSTGRES) {
            return pg.delete(refreshToken);
        }
        // 迁移期：会话可能仍在 PG（未刷新过的旧会话直接登出）
        return redis.delete(refreshToken) || pg.deleteRowByToken(refreshToken);
    }

    @Override
    public void revokeAll(Long userId) {
        if (properties.getMode() == Mode.POSTGRES) {
            pg.revokeAll(userId);
        } else {
            // redis 模式也顺带清 PG：消化迁移期前遗留的旧行，避免表无限增长
            redis.revokeAll(userId);
            pg.revokeAll(userId);
        }
    }

    /**
     * PG 行迁移到 Redis：先写 Redis（保留 createdAt/maxExpireAt，绝对期钳制），
     * 成功后删除该单行 PG 记录。Redis 写失败则 PG 行保留，下次仍可恢复。
     */
    private LocateResult migrateFromPg(String refreshToken) {
        Optional<UserSessionStore.SessionSnapshot> row = pg.findCurrentIncludingExpired(refreshToken);
        if (row.isEmpty()) {
            return new LocateResult.NotFound();
        }
        UserSessionStore.SessionSnapshot snapshot = row.get();
        if (!snapshot.maxExpireAt().isAfter(Instant.now())) {
            // 超过 24h 绝对期的旧行：直接清理，用户重新登录
            pg.deleteRowByToken(refreshToken);
            return new LocateResult.NotFound();
        }
        redis.create(new UserSessionStore.SessionCreate(
                snapshot.userId(), refreshToken, snapshot.device(), snapshot.ipAddress(),
                snapshot.createdAt(), snapshot.maxExpireAt()));
        pg.deleteRowByToken(refreshToken);
        log.info("migrated user session from postgres to redis, userId={}", snapshot.userId());
        return new LocateResult.Found(snapshot);
    }
}
