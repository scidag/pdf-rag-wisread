package com.wisread.security;

import com.wisread.config.WisreadRateLimitProperties;
import com.wisread.config.WisreadTrustProxyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * 认证接口限流过滤器（FR-9）。
 *
 * <p>覆盖 login / register / refresh 三个认证敏感接口，按客户端 IP 限流，
 * 缓解暴力破解与刷新接口刷量。计数存储于 Redis（多实例一致），
 * Redis 故障时由 {@link AuthRateLimiter} 内部降级为本地 Caffeine 兜底。
 *
 * <p>IP 解析（审计 M1）：默认取 TCP 对端地址（X-Forwarded-For 伪造无效）；
 * 仅当 {@code wisread.trust-proxy=true}（部署于可信反向代理之后）才取
 * X-Forwarded-For 首个地址。
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    // 固定窗口长度：1 分钟
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final AuthRateLimiter rateLimiter;
    private final WisreadRateLimitProperties properties;
    private final WisreadTrustProxyProperties trustProxyProperties;

    public AuthRateLimitFilter(AuthRateLimiter rateLimiter,
                               WisreadRateLimitProperties properties,
                               WisreadTrustProxyProperties trustProxyProperties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.trustProxyProperties = trustProxyProperties;
    }

    /**
     * 判断当前请求是否需要限流。
     * 仅对 POST 方式的 /auth/login、/auth/register 与 /auth/refresh 生效，
     * 其它请求直接放行。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !path.endsWith("/auth/login")
                && !path.endsWith("/auth/register")
                && !path.endsWith("/auth/refresh");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean refresh = path.endsWith("/auth/refresh");
        String bucket = refresh ? "refresh" : "auth";
        int limit = refresh ? properties.getRefreshPerMinute() : properties.getAuthPerMinute();

        String key = bucket + ":" + resolveClientIp(request);
        if (!rateLimiter.tryAcquire(key, limit, WINDOW)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":429,\"message\":\"too many requests\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 解析客户端真实 IP。
     * trust-proxy=false（默认）：TCP 对端地址，客户端伪造 XFF 头无效；
     * trust-proxy=true：取 X-Forwarded-For 首个地址（可信代理填充）。
     */
    private String resolveClientIp(HttpServletRequest request) {
        if (trustProxyProperties.isTrustProxy()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
        }
        return request.getRemoteAddr();
    }
}
