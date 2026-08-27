package com.wisread.service.impl;

import com.wisread.service.AuthService;

import com.wisread.config.WisreadJwtProperties;
import com.wisread.dto.AuthResponse;
import com.wisread.dto.LoginRequest;
import com.wisread.dto.RegisterRequest;
import com.wisread.dto.UserResponse;
import com.wisread.entity.User;
import com.wisread.entity.UserSession;
import com.wisread.exception.ApiException;
import com.wisread.repository.UserRepository;
import com.wisread.repository.UserSessionRepository;
import com.wisread.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

/**
 * 认证服务实现（AuthServiceImpl）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>令牌签发：登录/注册成功后调用 {@code issueTokens}，生成 Access + Refresh 令牌，
 *       并把 Refresh Token 的 SHA-256 哈希写入 {@code UserSession}（不存明文）。</li>
 *   <li>令牌刷新：采用“轮换 + 重放检测”策略——刷新时把旧哈希存入 previous 字段、写入新哈希；
 *       若客户端提交的是已轮换掉的旧令牌（命中 previous 哈希），则视为泄露，吊销该用户全部会话。</li>
 *   <li>登出：按 Refresh Token 哈希删除对应会话，实现令牌吊销。</li>
 *   <li>密码安全：统一使用 BCrypt 加密，业务层永不接触明文。</li>
 * </ul>
 *
 * <p>所有写操作均标注 {@code @Transactional}，保证用户与会话数据的一致性。
 */
@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final JwtService jwtService;
    private final WisreadJwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthServiceImpl(
            UserRepository userRepository,
            UserSessionRepository userSessionRepository,
            JwtService jwtService,
            WisreadJwtProperties jwtProperties
    ) {
        this.userRepository = userRepository;
        this.userSessionRepository = userSessionRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    /**
     * 注册新用户。
     *
     * <p>做什么：校验用户名/邮箱唯一性后创建用户，密码经 BCrypt 加密落库，并直接签发令牌。
     * 为什么：注册即登录可简化前端流程；冲突时抛 409 让前端提示“已存在”。
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // 用户名唯一性校验，冲突返回 409
        if (userRepository.existsByUsername(request.username())) {
            throw new ApiException(HttpStatus.CONFLICT, "username already exists");
        }
        // 邮箱唯一性校验，冲突返回 409
        if (userRepository.existsByEmail(request.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "email already exists");
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        // 密码绝不明文存储，统一使用 BCrypt 哈希
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.insert(user);

        // 注册即签发令牌（设备/IP 为空，由后续登录补全）
        return issueTokens(user, null, null);
    }

    /**
     * 用户登录。
     *
     * <p>做什么：按邮箱查找并校验密码、账号状态，全部通过后签发令牌。
     * 为什么：① 凭据错误不区分“邮箱不存在”还是“密码错”，统一 401 防止账号枚举；
     * ② 账号状态非 1（正常）时拒绝登录，实现封禁能力。
     */
    @Transactional
    public AuthResponse login(LoginRequest request, String device, String ipAddress) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid credentials"));
        // 校验密码哈希是否匹配（防时序攻击由 BCrypt 本身处理）
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        // 账号状态校验：状态为空或非 1 视为被禁用
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "account disabled");
        }
        return issueTokens(user, device, ipAddress);
    }

    /**
     * 刷新访问令牌（核心安全逻辑）。
     *
     * <p>做什么：
     * <ol>
     *   <li>校验 Refresh Token 非空且能通过哈希匹配到一个未过期会话；</li>
     *   <li>若匹配失败，进一步用 previous 哈希排查“旧令牌重放”，命中则吊销该用户全部会话；</li>
     *   <li>匹配成功则轮换令牌——把当前哈希归档到 previous、写入新哈希，并刷新设备/IP/过期时间；</li>
     *   <li>签发新的 Access Token 返回。</li>
     * </ol>
     * 为什么：令牌轮换（rotate）使旧 Refresh Token 立即失效，降低泄露面；
     * 重放检测能在旧令牌被攻击者复用时第一时间吊销所有会话，杜绝盗用。
     */
    @Transactional
    public AuthResponse refresh(String refreshToken, String device, String ipAddress) {
        // 缺失 Refresh Token 直接拒绝
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "missing refresh token");
        }
        // 计算令牌哈希用于比对，避免在库表中保存明文令牌
        String hash = sha256(refreshToken);
        Instant now = Instant.now();
        UserSession session = userSessionRepository
                .findByRefreshTokenHashAndExpiresAtAfter(hash, now)
                .orElse(null);

        if (session == null) {
            // 重用检测：命中上一代哈希说明这是已被轮换过的旧 token（可能泄露），
            // 立即撤销该用户的所有会话。
            UserSession stale = userSessionRepository
                    .findByPreviousRefreshTokenHashAndExpiresAtAfter(hash, now)
                    .orElse(null);
            if (stale != null) {
                // 安全处置：疑似泄露，吊销该用户所有会话，阻止旧令牌继续被利用
                userSessionRepository.deleteByUserId(stale.getUserId());
                throw new ApiException(HttpStatus.UNAUTHORIZED, "refresh token reuse detected");
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }

        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "user not found"));

        // 生成新 Refresh Token 并轮换：旧哈希归档，写入新哈希
        String newRefreshToken = jwtService.createRefreshToken(user.getId(), user.getUsername(), rolesOf(user));
        session.setPreviousRefreshTokenHash(session.getRefreshTokenHash());
        session.setRefreshTokenHash(sha256(newRefreshToken));
        session.setDevice(device);
        session.setIpAddress(ipAddress);
        session.setExpiresAt(Instant.now().plus(jwtProperties.getRefreshTokenTtl()));
        userSessionRepository.updateById(session);

        // 签发新的短期 Access Token
        String accessToken = jwtService.createAccessToken(user.getId(), user.getUsername(), rolesOf(user));
        long expiresIn = jwtProperties.getAccessTokenTtl().toSeconds();
        return new AuthResponse(accessToken, newRefreshToken, expiresIn, toResponse(user));
    }

    /**
     * 登出（吊销会话）。
     *
     * <p>做什么：将 Refresh Token 对应的会话从库表删除，使其后续无法再用于刷新令牌。
     * 为什么：实现“主动登出即让令牌失效”，是令牌黑名单的最简实现。
     */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            String hash = sha256(refreshToken);
            userSessionRepository
                    .findByRefreshTokenHashAndExpiresAtAfter(hash, Instant.now())
                    .ifPresent(session -> userSessionRepository.deleteById(session.getId()));
        }
    }

    /**
     * 获取当前登录用户的基础信息。
     *
     * @param userId 当前登录用户 ID
     * @return 用户资料响应（仅含 ID/用户名/邮箱）
     */
    public UserResponse getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user not found"));
        return toResponse(user);
    }

    /**
     * 签发令牌并落地会话。
     *
     * <p>做什么：生成 Access/Refresh 令牌，将 Refresh Token 的哈希写入 {@code UserSession}，
     * 同时记录设备/IP 与过期时间。为什么：集中封装签发逻辑，注册与登录复用同一套逻辑。
     */
    private AuthResponse issueTokens(User user, String device, String ipAddress) {
        Set<String> roles = rolesOf(user);
        String accessToken = jwtService.createAccessToken(user.getId(), user.getUsername(), roles);
        String refreshToken = jwtService.createRefreshToken(user.getId(), user.getUsername(), roles);

        UserSession session = new UserSession();
        session.setUserId(user.getId());
        // 仅保存哈希，避免数据库泄露导致令牌被直接利用
        session.setRefreshTokenHash(sha256(refreshToken));
        session.setDevice(device);
        session.setIpAddress(ipAddress);
        session.setExpiresAt(Instant.now().plus(jwtProperties.getRefreshTokenTtl()));
        userSessionRepository.insert(session);

        long expiresIn = jwtProperties.getAccessTokenTtl().toSeconds();
        return new AuthResponse(accessToken, refreshToken, expiresIn, toResponse(user));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail());
    }

    private Set<String> rolesOf(User user) {
        return Set.of(user.getRole().name());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
