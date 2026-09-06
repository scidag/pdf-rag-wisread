package com.wisread.controller;

import com.wisread.config.WisreadJwtProperties;
import com.wisread.dto.AuthResponse;
import com.wisread.dto.LoginRequest;
import com.wisread.dto.RegisterRequest;
import com.wisread.security.TokenBlacklistService;
import com.wisread.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证（Auth）控制器。
 * 负责用户注册、登录、令牌刷新、登出等认证相关的 REST 端点。
 * 鉴权采用 access token（Bearer） + refresh token（HttpOnly Cookie）机制；
 * refresh token 通过名为 wisread_refresh 的 Cookie 下发与读取（响应体不返回，FR-3）。
 * 基础路径：/api/v1/auth
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    // refresh token 在 Cookie 中使用的名称
    public static final String REFRESH_COOKIE = "wisread_refresh";
    // refresh token Cookie 对应的路径
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth/refresh";
    // 旧版本曾以 /api/v1/auth 下发同名 Cookie，登录/登出时顺带清除，避免残留 token 干扰读取
    private static final String LEGACY_REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final TokenBlacklistService tokenBlacklistService;
    private final WisreadJwtProperties jwtProperties;

    public AuthController(AuthService authService, TokenBlacklistService tokenBlacklistService,
                          WisreadJwtProperties jwtProperties) {
        this.authService = authService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.jwtProperties = jwtProperties;
    }

    /** POST /api/v1/auth/register：用户注册接口。
     * 入参：@Valid RegisterRequest（用户名、邮箱、密码），并通过 HttpServletResponse 写回 refresh Cookie。
     * 业务含义：创建新用户账号，调用 AuthService 完成注册并返回登录态。
     * 返回：201 Created 及认证信息（accessToken / 过期时间 / 用户资料）。 */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        AuthResponse authResponse = authService.register(request);
        setRefreshCookie(authResponse, httpRequest, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(authResponse);
    }

    /** POST /api/v1/auth/login：用户登录接口。
     * 入参：@Valid LoginRequest（邮箱、密码），并读取请求头 User-Agent 与客户端 IP 用于风控/审计。
     * 业务含义：校验凭证，签发 access token 与 refresh token（后者写入 HttpOnly Cookie）。
     * 返回：200 OK 及认证信息。 */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        AuthResponse authResponse = authService.login(request, httpRequest.getHeader("User-Agent"), httpRequest.getRemoteAddr());
        setRefreshCookie(authResponse, httpRequest, response);
        return ResponseEntity.ok(authResponse);
    }

    /** POST /api/v1/auth/refresh：刷新访问令牌接口。
     * 入参：无请求体，从 Cookie 读取 refresh token；同时读取 User-Agent 与 IP。
     * 业务含义：用有效的 refresh token 重新签发一对新的 access/refresh token；
     * 并发竞态宽限场景下重发同一最新 refresh token（Cookie 收敛，FR-8）。
     * 返回：200 OK 及新的认证信息。 */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            HttpServletRequest httpRequest,
            HttpServletResponse response
    ) {
        String refreshToken = readRefreshCookie(httpRequest);
        if (refreshToken == null) {
            // 登出幂等性：refresh 端点对缺失 Cookie 明确返回 401
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        AuthResponse authResponse = authService.refresh(refreshToken, httpRequest.getHeader("User-Agent"), httpRequest.getRemoteAddr());
        setRefreshCookie(authResponse, httpRequest, response);
        return ResponseEntity.ok(authResponse);
    }

    /** POST /api/v1/auth/logout：用户登出接口。
     * 入参：无请求体，从请求头读取 Bearer access token，并从 Cookie 读取 refresh token。
     * 业务含义：将当前 access token 与 refresh token 一并加入黑名单（TTL=剩余有效期，FR-2），
     * 吊销 refresh token 会话，并清除 refresh Cookie。
     * 返回：204 No Content（幂等：重复登出不报错）。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest, HttpServletResponse response) {
        // 立即吊销当前的 access token（黑名单 TTL = 其剩余有效期）
        String accessToken = extractBearer(httpRequest);
        if (accessToken != null) {
            tokenBlacklistService.blacklist(accessToken);
        }
        String refreshToken = readRefreshCookie(httpRequest);
        if (refreshToken != null) {
            // FR-2：refresh token 本身也拉黑，登出后不可再用于刷新
            tokenBlacklistService.blacklist(refreshToken);
        }
        authService.logout(refreshToken);
        deleteCookie(response, REFRESH_COOKIE_PATH);
        deleteCookie(response, LEGACY_REFRESH_COOKIE_PATH);
        return ResponseEntity.noContent().build();
    }

    // 从 Authorization 请求头中提取 Bearer access token（"Bearer " 之后部分），不存在则返回 null
    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    // 将 refresh token 以 HttpOnly + SameSite=Lax 的 Cookie 形式下发，
    // Max-Age 与会话绝对有效期一致（FR-5：默认 24 小时）
    private void setRefreshCookie(AuthResponse authResponse, HttpServletRequest request, HttpServletResponse response) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, authResponse.refreshToken());
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath(REFRESH_COOKIE_PATH);
        cookie.setAttribute("SameSite", "Lax");
        cookie.setMaxAge((int) jwtProperties.getRefreshTokenTtl().toSeconds());
        response.addCookie(cookie);
        deleteCookie(response, LEGACY_REFRESH_COOKIE_PATH);
    }

    // 以 Max-Age=0 覆盖同名 Cookie，清除旧路径下可能残留的 refresh token
    private void deleteCookie(HttpServletResponse response, String path) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath(path);
        cookie.setAttribute("SameSite", "Lax");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    // 从请求 Cookie 中读取名为 wisread_refresh 的 refresh token；缺失时返回 null
    // （refresh 端点对 null 显式返回 401；logout 幂等容忍缺失）
    private String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        Cookie fallback = null;
        for (Cookie cookie : cookies) {
            if (!REFRESH_COOKIE.equals(cookie.getName())) {
                continue;
            }
            // 历史遗留同名 Cookie 可能有多个：优先取当前路径，其余留作兜底
            if (REFRESH_COOKIE_PATH.equals(cookie.getPath())) {
                return cookie.getValue();
            }
            fallback = cookie;
        }
        return fallback != null ? fallback.getValue() : null;
    }
}
