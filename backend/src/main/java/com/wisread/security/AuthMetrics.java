package com.wisread.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证监控指标（需求文档第七章）。
 *
 * <p>Prometheus 命名约定：点号转为下划线并追加 _total，
 * 如 {@code auth.login.success} → {@code auth_login_success_total}。
 * registry 缺席（如纯单元测试上下文）时退化为 {@link SimpleMeterRegistry}，不影响业务。
 */
@Component
public class AuthMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> loginFailCounters = new ConcurrentHashMap<>();

    public AuthMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        // registry 缺席（如纯单元测试直接 new）时退化为 SimpleMeterRegistry
        this.registry = registryProvider == null
                ? new SimpleMeterRegistry()
                : registryProvider.getIfAvailable(SimpleMeterRegistry::new);
    }

    /** 登录成功。 */
    public void loginSuccess() {
        registry.counter("auth.login.success").increment();
    }

    /** 登录失败（reason：invalid_credentials / disabled / rate_limited）。 */
    public void loginFail(String reason) {
        loginFailCounters
                .computeIfAbsent(reason, r -> registry.counter("auth.login.fail", "reason", r))
                .increment();
    }

    /** 刷新成功（迁移观察期核心指标）。 */
    public void refreshSuccess() {
        registry.counter("auth.refresh.success").increment();
    }

    /** 重放检测命中——区分真攻击与客户端 bug 的关键指标。 */
    public void refreshReplay() {
        registry.counter("auth.refresh.replay").increment();
    }

    /** PREVIOUS 宽限触发（持续偏高 = 前端并发逻辑缺陷）。 */
    public void refreshGrace() {
        registry.counter("auth.refresh.grace").increment();
    }

    /** 会话存储（Redis/双读）错误。 */
    public void sessionError() {
        registry.counter("redis.session.error").increment();
    }

    /** 限流本地兜底触发（> 0 持续即 Redis 异常信号）。 */
    public void rateLimitFallback() {
        registry.counter("redis.rl.fallback").increment();
    }
}
