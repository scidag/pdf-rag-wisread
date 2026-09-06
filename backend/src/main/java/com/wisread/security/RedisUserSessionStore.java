package com.wisread.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wisread.security.UserSessionStore.hashToken;

/**
 * 会话存储的 Redis 实现（FR-7 / FR-8）。
 *
 * <p>数据结构：
 * <pre>
 * wisread:session:hash:&lt;sha256&gt;  Hash{userId, device, ip, createdAt, maxExpireAt, prevHash}
 *                               TTL = maxExpireAt - now（固定截止，轮换重设同值）
 * wisread:session:prev:&lt;sha256&gt;  Hash{newHash, device, ip, ts, tokenEnc}
 *                               TTL = 会话剩余（与所属会话同生共死，保证超窗旧令牌仍能命中重放检测）
 * wisread:session:user:&lt;uid&gt;    ZSET{member=hash, score=maxExpireAt}
 *                               key TTL = max(maxExpireAt)，登录时设置；轮换 ZADD 不触碰（不续期）
 * </pre>
 *
 * <p>轮换通过 Lua 脚本原子完成（状态机：NORMAL / PREVIOUS / NOT_FOUND）；
 * 业务判断（宽限窗口、device/IP 指纹比较）由 AuthServiceImpl 在 Java 侧执行。
 * prev 条目暂存最新一代 refresh token 的 AES-256-GCM 密文（FR-8 宽限重发）。
 */
@Component
public class RedisUserSessionStore implements UserSessionStore {

    private static final String KEY_HASH_PREFIX = "wisread:session:hash:";
    private static final String KEY_PREV_PREFIX = "wisread:session:prev:";
    private static final String KEY_USER_PREFIX = "wisread:session:user:";

    private static final String F_USER_ID = "userId";
    private static final String F_DEVICE = "device";
    private static final String F_IP = "ip";
    private static final String F_CREATED_AT = "createdAt";
    private static final String F_MAX_EXPIRE_AT = "maxExpireAt";
    private static final String F_PREV_HASH = "prevHash";
    private static final String F_NEW_HASH = "newHash";
    private static final String F_TS = "ts";
    private static final String F_TOKEN_ENC = "tokenEnc";

    /**
     * 原子轮换状态机（KEYS: hashOld, hashNew, prevOld, userUid）。
     * ARGV: [1]=sessionTtlSec [2]=prevTtlSec [3]=zaddScore [4]=newHash
     *       [5]=userId [6]=device [7]=ip [8]=createdAt [9]=maxExpireAt
     *       [10]=oldHash [11]=ts [12]=tokenEnc
     */
    private static final DefaultRedisScript<List> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                if redis.call('EXISTS', KEYS[3]) == 1 then
                    local p = redis.call('HGETALL', KEYS[3])
                    return {'PREVIOUS', unpack(p)}
                end
                return {'NOT_FOUND'}
            end
            redis.call('HSET', KEYS[2],
                'userId', ARGV[5], 'device', ARGV[6], 'ip', ARGV[7],
                'createdAt', ARGV[8], 'maxExpireAt', ARGV[9], 'prevHash', ARGV[10])
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[1]))
            redis.call('HSET', KEYS[3],
                'newHash', ARGV[4], 'device', ARGV[6], 'ip', ARGV[7],
                'ts', ARGV[11], 'tokenEnc', ARGV[12])
            redis.call('EXPIRE', KEYS[3], tonumber(ARGV[2]))
            redis.call('ZADD', KEYS[4], tonumber(ARGV[3]), ARGV[4])
            redis.call('ZREM', KEYS[4], ARGV[10])
            redis.call('DEL', KEYS[1])
            return {'NORMAL'}
            """, List.class);

    private final StringRedisTemplate redis;
    private final TokenCipher tokenCipher;
    private final AuthMetrics metrics;

    public RedisUserSessionStore(StringRedisTemplate redis, TokenCipher tokenCipher, AuthMetrics metrics) {
        this.redis = redis;
        this.tokenCipher = tokenCipher;
        this.metrics = metrics;
    }

    @Override
    public void create(SessionCreate cmd) {
        String hash = hashToken(cmd.refreshToken());
        String hashKey = KEY_HASH_PREFIX + hash;
        String userKey = KEY_USER_PREFIX + cmd.userId();
        Duration ttl = Duration.between(Instant.now(), cmd.maxExpireAt());
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException("session maxExpireAt must be in the future");
        }

        Map<String, String> fields = new HashMap<>();
        fields.put(F_USER_ID, String.valueOf(cmd.userId()));
        fields.put(F_DEVICE, nullToEmpty(cmd.device()));
        fields.put(F_IP, nullToEmpty(cmd.ipAddress()));
        fields.put(F_CREATED_AT, String.valueOf(cmd.createdAt().toEpochMilli()));
        fields.put(F_MAX_EXPIRE_AT, String.valueOf(cmd.maxExpireAt().toEpochMilli()));
        fields.put(F_PREV_HASH, "");

        redis.opsForHash().putAll(hashKey, fields);
        redis.expire(hashKey, ttl);
        // 索引 key TTL 对齐最后登录会话的绝对到期时刻（v2.2：轮换不触碰、自动清理、免维护任务）
        redis.opsForZSet().add(userKey, hash, cmd.maxExpireAt().toEpochMilli());
        redis.expire(userKey, ttl);
    }

    @Override
    public LocateResult locate(String refreshToken) {
        String hash = hashToken(refreshToken);

        Map<Object, Object> session = redis.opsForHash().entries(KEY_HASH_PREFIX + hash);
        if (!session.isEmpty()) {
            return new LocateResult.Found(toSnapshot(session));
        }
        Map<Object, Object> prev = redis.opsForHash().entries(KEY_PREV_PREFIX + hash);
        if (!prev.isEmpty()) {
            return new LocateResult.Previous(toGraceContext(prev));
        }
        return new LocateResult.NotFound();
    }

    @Override
    public RotateOutcome rotate(SessionSnapshot snapshot, String oldToken, String newToken,
                                 String device, String ipAddress) {
        String oldHash = hashToken(oldToken);
        String newHash = hashToken(newToken);
        Instant now = Instant.now();
        Duration remaining = Duration.between(now, snapshot.maxExpireAt());
        if (remaining.isNegative() || remaining.isZero()) {
            return new RotateOutcome.NotFound();
        }

        List<?> result = redis.execute(
                ROTATE_SCRIPT,
                List.of(KEY_HASH_PREFIX + oldHash, KEY_HASH_PREFIX + newHash,
                        KEY_PREV_PREFIX + oldHash, KEY_USER_PREFIX + snapshot.userId()),
                String.valueOf(remaining.toSeconds()),
                String.valueOf(remaining.toSeconds()),
                String.valueOf(snapshot.maxExpireAt().toEpochMilli()),
                newHash,
                String.valueOf(snapshot.userId()),
                nullToEmpty(device),
                nullToEmpty(ipAddress),
                String.valueOf(snapshot.createdAt().toEpochMilli()),
                String.valueOf(snapshot.maxExpireAt().toEpochMilli()),
                oldHash,
                String.valueOf(now.toEpochMilli()),
                tokenCipher.encrypt(newToken));

        return toOutcome(result, snapshot, device, ipAddress);
    }

    @Override
    public boolean delete(String refreshToken) {
        String hash = hashToken(refreshToken);
        String hashKey = KEY_HASH_PREFIX + hash;

        Map<Object, Object> session = redis.opsForHash().entries(hashKey);
        if (session.isEmpty()) {
            return false;
        }
        // 连同上一代 prev 链一起清理，登出后旧令牌不可经宽限路径复活
        String prevHash = str(session.get(F_PREV_HASH));
        redis.delete(hashKey);
        if (prevHash != null && !prevHash.isBlank()) {
            redis.delete(KEY_PREV_PREFIX + prevHash);
        }
        Object userId = session.get(F_USER_ID);
        if (userId != null) {
            redis.opsForZSet().remove(KEY_USER_PREFIX + userId, hash);
        }
        return true;
    }

    @Override
    public void revokeAll(Long userId) {
        String userKey = KEY_USER_PREFIX + userId;
        Set<String> members = redis.opsForZSet().range(userKey, 0, -1);
        if (members != null) {
            for (String member : members) {
                Map<Object, Object> session = redis.opsForHash().entries(KEY_HASH_PREFIX + member);
                String prevHash = str(session.get(F_PREV_HASH));
                redis.delete(KEY_HASH_PREFIX + member);
                if (prevHash != null && !prevHash.isBlank()) {
                    redis.delete(KEY_PREV_PREFIX + prevHash);
                }
            }
        }
        redis.delete(userKey);
    }

    private RotateOutcome toOutcome(List<?> result, SessionSnapshot snapshot,
                                     String device, String ipAddress) {
        if (result == null || result.isEmpty()) {
            metrics.sessionError();
            throw new IllegalStateException("rotate script returned empty result");
        }
        String state = String.valueOf(result.get(0));
        if ("NORMAL".equals(state)) {
            return new RotateOutcome.Found(snapshot);
        }
        if ("PREVIOUS".equals(state)) {
            // Lua 返回 ['PREVIOUS', k1, v1, k2, v2, ...] 的扁平结构
            Map<String, String> prev = new HashMap<>();
            for (int i = 1; i + 1 < result.size(); i += 2) {
                prev.put(String.valueOf(result.get(i)), String.valueOf(result.get(i + 1)));
            }
            return new RotateOutcome.Previous(toGraceContext(prev));
        }
        return new RotateOutcome.NotFound();
    }

    private SessionSnapshot toSnapshot(Map<Object, Object> fields) {
        return new SessionSnapshot(
                Long.parseLong(str(fields.get(F_USER_ID))),
                str(fields.get(F_DEVICE)),
                str(fields.get(F_IP)),
                Instant.ofEpochMilli(Long.parseLong(str(fields.get(F_CREATED_AT)))),
                Instant.ofEpochMilli(Long.parseLong(str(fields.get(F_MAX_EXPIRE_AT)))));
    }

    private GraceContext toGraceContext(Map<?, ?> fields) {
        return new GraceContext(
                str(fields.get(F_NEW_HASH)),
                str(fields.get(F_DEVICE)),
                str(fields.get(F_IP)),
                Instant.ofEpochMilli(Long.parseLong(str(fields.get(F_TS)))),
                str(fields.get(F_TOKEN_ENC)));
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
