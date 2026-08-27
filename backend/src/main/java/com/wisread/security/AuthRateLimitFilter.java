package com.wisread.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录/注册接口限流过滤器。
 * 针对认证相关的敏感接口（login、register）按客户端 IP 做滑动窗口限流，
 * 缓解暴力破解与注册接口被刷。每个 IP 在 1 分钟内最多允许 10 次尝试。
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    // 单 IP 在窗口内允许的最大尝试次数
    private static final int MAX_ATTEMPTS = 10;
    // 滑动窗口长度：1 分钟
    private static final Duration WINDOW = Duration.ofMinutes(1);

    // 单实例内存限流，窗口过期后由下次请求清理；多实例部署时换成 Redis 计数或网关限流
    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    /**
     * 判断当前请求是否需要跳过限流。
     * 仅对 POST 方式的 /auth/login 与 /auth/register 生效，
     * 其它请求（非认证接口）直接放行，不做计数。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !"POST".equalsIgnoreCase(request.getMethod())
                || (!path.endsWith("/auth/login") && !path.endsWith("/auth/register"));
    }

    /**
     * 限流核心逻辑：滑动窗口计数。
     * 以客户端 IP 为维度记录每次请求时间戳，先剔除窗口外的旧记录，
     * 若窗口内次数已达上限则返回 429；否则记录本次请求并放行。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Instant now = Instant.now();
        // 按 IP 获取（或初始化）其请求时间戳队列
        Deque<Instant> window = attempts.computeIfAbsent(request.getRemoteAddr(), key -> new ArrayDeque<>());
        synchronized (window) {
            // 移除超出窗口长度的旧时间戳，维持滑动窗口
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) {
                window.removeFirst();
            }
            // 窗口内次数已满，直接拒绝并返回 429
            if (window.size() >= MAX_ATTEMPTS) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":429,\"message\":\"too many requests\"}");
                return;
            }
            // 记录本次请求时间后放行
            window.addLast(now);
        }
        filterChain.doFilter(request, response);
    }
}
