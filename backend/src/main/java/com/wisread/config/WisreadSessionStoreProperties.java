package com.wisread.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话存储模式配置（FR-7 双读分阶段迁移）。
 *
 * <p>迁移路径：{@code postgres} → {@code redis-migrate}（双读，PG 兜底）→ {@code redis}。
 */
@ConfigurationProperties(prefix = "wisread.session-store")
public class WisreadSessionStoreProperties {

    public enum Mode {
        /** 会话仅存 PostgreSQL（R1 阶段，兼容旧行为）。 */
        POSTGRES,
        /** 双读：写 Redis；读 Redis 未命中回落 PG 并迁移该行（R2 灰度阶段）。 */
        REDIS_MIGRATE,
        /** 会话仅存 Redis（R2 稳定阶段）。 */
        REDIS
    }

    /**
     * 会话存储模式，默认 postgres。
     * 切换到 redis 前应保持 redis-migrate 观察 3-7 天：
     * 刷新成功率无下跌、redis_session_error_total ≈ 0、PG 命中率趋 0。
     */
    private Mode mode = Mode.POSTGRES;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }
}
