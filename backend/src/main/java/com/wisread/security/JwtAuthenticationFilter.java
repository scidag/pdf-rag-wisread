package com.wisread.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.wisread.entity.User;
import com.wisread.repository.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器。
 * 作为请求前置过滤器，解析 Authorization 头中的 Bearer Token，
 * 校验其合法性、是否在黑名单、以及对应账号状态，最终将认证信息
 * 写入 Spring Security 上下文，供后续控制器获取当前用户与权限。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            TokenBlacklistService tokenBlacklistService,
            UserRepository userRepository
    ) {
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.userRepository = userRepository;
    }

    /**
     * 每请求执行一次：解析并设置认证上下文。
     * 仅当存在 Bearer Token 时尝试认证；任何解析/校验失败或用户被禁用
     * 都会清空上下文，使请求以匿名身份进入（由 SecurityConfig 决定最终放行与否）。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                // 去除 "Bearer " 前缀得到原始 token
                String token = header.substring(7);
                // 已登出/作废的 token 视为非法
                if (tokenBlacklistService.isBlacklisted(token)) {
                    throw new JwtException("token is blacklisted");
                }
                // 解析出用户 ID
                Long userId = jwtService.parseUserId(token);
                // 加载用户以校验账号是否存在及是否被禁用
                User user = userRepository.findById(userId).orElse(null);
                // 用户不存在或状态非正常（status != 1）则清除上下文，拒绝认证
                if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                    SecurityContextHolder.clearContext();
                } else {
                    // 正常用户：构造认证对象，principal 为 userId，并附上 ROLE_ 前缀的角色权限
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userId,
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                            );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception ignored) {
                // 任何异常（签名错、过期、格式错）都视为未认证，清空上下文，不泄露细节
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
