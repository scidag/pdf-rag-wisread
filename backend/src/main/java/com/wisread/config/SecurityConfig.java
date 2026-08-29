package com.wisread.config;

import com.wisread.security.JwtAuthenticationFilter;
import com.wisread.security.AuthRateLimitFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 主配置。
 * 定义“智阅”系统的安全规则：无状态会话（JWT）、认证/授权异常处理、
 * 公开与受保护接口划分，并将自定义过滤器（登录限流、JWT 认证）织入过滤器链。
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthRateLimitFilter authRateLimitFilter;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthRateLimitFilter authRateLimitFilter
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authRateLimitFilter = authRateLimitFilter;
    }

    /**
     * 构建安全过滤器链。
     * 关闭 CSRF（无状态 API 由 JWT 保护）、开启 CORS、采用无状态会话，
     * 放行认证/健康检查等接口，管理员接口需 ADMIN 角色，其余一律需鉴权；
     * 并注册自定义入口/拒绝处理器与过滤器顺序。
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 无状态 JWT 鉴权不依赖 Cookie，关闭 CSRF 防护
                .csrf(AbstractHttpConfigurer::disable)
                // 复用 CorsConfig 的跨域规则
                .cors(Customizer.withDefaults())
                // 不创建 HttpSession，每次请求携带 JWT 自认证
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 错误与异步分发类型直接放行，避免框架内部转发被拦截
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/health",
                                "/actuator/health",
                                "/error"
                        ).permitAll() // 认证与探活接口允许匿名访问
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN") // 管理后台仅限管理员
                        .anyRequest().authenticated() // 其余接口均需登录
                )
                .exceptionHandling(handling -> handling
                        // 未携带/无效凭证时返回 401 JSON，而非重定向登录页
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":401,\"message\":\"unauthorized\"}");
                        })
                        // 已登录但权限不足时返回 403 JSON
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":403,\"message\":\"forbidden\"}");
                        })
                )
                // 限流过滤器先于 JWT 过滤器执行，命中限流则直接拒绝，避免无谓解析
                .addFilterBefore(authRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                // JWT 认证过滤器在用户名密码过滤器之前解析并写入认证信息
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
