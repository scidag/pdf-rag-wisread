package com.wisread.service.impl;

import com.wisread.config.WisreadAuthProperties;
import com.wisread.config.WisreadJwtProperties;
import com.wisread.dto.AuthResponse;
import com.wisread.dto.LoginRequest;
import com.wisread.dto.RegisterRequest;
import com.wisread.dto.UserResponse;
import com.wisread.entity.User;
import com.wisread.exception.ApiException;
import com.wisread.repository.UserRepository;
import com.wisread.security.AuthMetrics;
import com.wisread.security.JwtService;
import com.wisread.security.TokenBlacklistService;
import com.wisread.security.TokenCipher;
import com.wisread.security.UserSessionStore;
import com.wisread.security.UserSessionStore.GraceContext;
import com.wisread.security.UserSessionStore.LocateResult;
import com.wisread.security.UserSessionStore.RotateOutcome;
import com.wisread.security.UserSessionStore.SessionCreate;
import com.wisread.security.UserSessionStore.SessionSnapshot;
import com.wisread.service.AuthService;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 认证服务实现（AuthServiceImpl）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>令牌签发：登录/注册成功后生成 Access + Refresh 令牌，会话经
 *       {@link UserSessionStore} 落地（postgres / redis-migrate / redis 三种模式）。</li>
 *   <li>令牌类型隔离（FR-1）：refresh 接口仅接受 typ=refresh 的令牌
 *       （过渡期兼容无 typ 的旧令牌），防止 access/refresh 混用。</li>
 *   <li>黑名单（FR-2）：refresh 令牌与 access 令牌登出后均按 jti 进入 Redis 黑名单。</li>
 *   <li>绝对有效期（FR-5）：会话自创建起 24 小时后必须重新登录，轮换不延长。</li>
 *   <li>哑哈希（FR-6）：用户不存在分支执行等代价的 BCrypt 比对，抹平响应时延防邮箱枚举。</li>
 *   <li>刷新轮换（FR-8）：存储层原子轮换返回状态机结果，PREVIOUS 宽限判定
 *       （窗口/device/IP 指纹）在 Java 侧执行；宽限通过时重发同一最新 refresh token，
 *       多标签页 Cookie 收敛、轮换代不变。</li>
 * </ul>
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final WisreadJwtProperties jwtProperties;
    private final WisreadAuthProperties authProperties;
    private final UserSessionStore sessionStore;
    private final TokenBlacklistService tokenBlacklistService;
    private final TokenCipher tokenCipher;
    private final AuthMetrics metrics;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public AuthServiceImpl(
            UserRepository userRepository,
            JwtService jwtService,
            WisreadJwtProperties jwtProperties,
            WisreadAuthProperties authProperties,
            UserSessionStore sessionStore,
            TokenBlacklistService tokenBlacklistService,
            TokenCipher tokenCipher,
            AuthMetrics metrics
    ) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.authProperties = authProperties;
        this.sessionStore = sessionStore;
        this.tokenBlacklistService = tokenBlacklistService;
        this.tokenCipher = tokenCipher;
        this.metrics = metrics;
        this.passwordEncoder = new BCryptPasswordEncoder();
        // FR-6：哑哈希用于抹平“用户不存在”与“密码比对”的时延差；
        // 未配置时启动随机生成（时延特征一致，同 BCrypt cost）
        String configured = authProperties.getDummyPasswordHash();
        this.dummyPasswordHash = (configured != null && !configured.isBlank())
                ? configured
                : this.passwordEncoder.encode(UUID.randomUUID().toString());
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
     * 用户不存在时执行哑哈希比对抹平时延（FR-6）；
     * ② 账号状态非 1（正常）时拒绝登录，实现封禁能力。
     */
    @Transactional
    public AuthResponse login(LoginRequest request, String device, String ipAddress) {
        User user = userRepository.findByEmail(request.email()).orElse(null);
        if (user == null) {
            // FR-6：对哑哈希执行同代价 BCrypt 比对再失败，消除响应时延侧信道
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            metrics.loginFail("invalid_credentials");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        // 校验密码哈希是否匹配
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            metrics.loginFail("invalid_credentials");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        // 账号状态校验：状态为空或非 1 视为被禁用
        if (user.getStatus() == null || user.getStatus() != 1) {
            metrics.loginFail("disabled");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "account disabled");
        }
        metrics.loginSuccess();
        return issueTokens(user, device, ipAddress);
    }

    /**
     * 刷新访问令牌（核心安全逻辑）。
     *
     * <p>流程：typ 校验（FR-1）→ jti 黑名单校验（FR-2）→ 定位会话 →
     * 命中上一代时做宽限/重放判定（FR-8）→ 正常路径原子轮换并签发新令牌。
     * Redis 模式下 PREVIOUS 宽限通过时重发同一最新 refresh token（Cookie 收敛），
     * 判定重放则吊销该用户全部会话。
     */
    @Transactional
    public AuthResponse refresh(String refreshToken, String device, String ipAddress) {
        // 缺失 Refresh Token 直接拒绝
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "missing refresh token");
        }

        // FR-1：令牌类型校验——仅 refresh 令牌可用于本接口
        String typ;
        try {
            typ = jwtService.parseType(refreshToken);
        } catch (JwtException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }
        if (!JwtService.TYP_REFRESH.equals(typ)
                && !(authProperties.isLegacyTypAccepted() && typ == null)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }

        // FR-2：登出后 refresh 令牌的 jti 已入黑名单，不可再使用
        if (tokenBlacklistService.isBlacklisted(refreshToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }

        return switch (sessionStore.locate(refreshToken)) {
            case LocateResult.Replay replay -> {
                // 命中上一代哈希（PG 模式）：判定泄露，吊销该用户所有会话
                sessionStore.revokeAll(replay.userId());
                metrics.refreshReplay();
                throw new ApiException(HttpStatus.UNAUTHORIZED, "refresh token reuse detected");
            }
            case LocateResult.NotFound nf ->
                    throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
            case LocateResult.Previous previous -> handleGrace(previous.ctx(), device, ipAddress);
            case LocateResult.Found found ->
                    rotateAndIssue(found.session(), refreshToken, device, ipAddress);
        };
    }

    /**
     * 登出（吊销会话）。
     *
     * <p>做什么：将 Refresh Token 对应的会话从存储删除，使其后续无法再用于刷新令牌。
     * 为什么：实现“主动登出即让令牌失效”；refresh 令牌的 jti 黑名单由控制器写入（FR-2）。
     */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            sessionStore.delete(refreshToken);
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
     * 正常轮换路径：定位到的会话仍在，加载用户、生成新令牌并原子轮换。
     */
    private AuthResponse rotateAndIssue(SessionSnapshot snapshot, String refreshToken,
                                        String device, String ipAddress) {
        User user = userRepository.findById(snapshot.userId()).orElse(null);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }

        String newRefreshToken = jwtService.createRefreshToken(
                user.getId(), user.getUsername(), rolesOf(user));
        RotateOutcome outcome = sessionStore.rotate(
                snapshot, refreshToken, newRefreshToken, device, ipAddress);

        return switch (outcome) {
            case RotateOutcome.Found f -> {
                metrics.refreshSuccess();
                yield buildResponse(user, newRefreshToken);
            }
            // 并发竞态：locate 与 rotate 之间另一请求已完成轮换 → 走宽限判定
            case RotateOutcome.Previous previous -> handleGrace(previous.ctx(), device, ipAddress);
            case RotateOutcome.Replay replay -> {
                sessionStore.revokeAll(replay.userId());
                metrics.refreshReplay();
                throw new ApiException(HttpStatus.UNAUTHORIZED, "refresh token reuse detected");
            }
            case RotateOutcome.NotFound nf ->
                    throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        };
    }

    /**
     * PREVIOUS 宽限处理（FR-8）。
     *
     * <p>prev 条目暂存了轮换时刻的最新一代 refresh token 密文：解密得到该 token，
     * 校验其会话仍然存活（登出/吊销后不可复活）、且轮换发生在宽限窗口内、
     * device 指纹一致（IP 漂移默认仅告警）。
     * 宽限通过 → 重发同一最新 token（Cookie 收敛，不产生新的轮换代）；
     * 任一判定失败 → 重放，吊销该用户全部会话。
     */
    private AuthResponse handleGrace(GraceContext ctx, String device, String ipAddress) {
        if (ctx.tokenEnc() == null || ctx.tokenEnc().isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }
        String latestToken;
        try {
            latestToken = tokenCipher.decrypt(ctx.tokenEnc());
        } catch (Exception e) {
            // 解密失败（密钥轮换边缘场景等）：fail-closed
            metrics.sessionError();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }

        // 目标会话必须仍然存活：登出/吊销/再轮换后，旧令牌不可经宽限路径复活
        LocateResult target = sessionStore.locate(latestToken);
        if (!(target instanceof LocateResult.Found found)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }
        User user = userRepository.findById(found.session().userId()).orElse(null);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }

        // 重放判定（Java 侧业务判断；Lua 只做状态转换）
        Instant now = Instant.now();
        boolean outsideWindow = ctx.ts() != null
                && ctx.ts().isBefore(now.minus(authProperties.getRefreshGraceWindow()));
        boolean deviceMismatch = !Objects.equals(ctx.device(), device);
        boolean ipMismatch = !Objects.equals(ctx.ipAddress(), ipAddress);
        if (outsideWindow || deviceMismatch
                || (authProperties.isRefreshGraceStrictIp() && ipMismatch)) {
            sessionStore.revokeAll(user.getId());
            metrics.refreshReplay();
            throw new ApiException(HttpStatus.UNAUTHORIZED, "refresh token reuse detected");
        }
        if (ipMismatch) {
            // 移动网络 WiFi/4G 切换属正常场景：默认仅记录，不拦截
            log.warn("refresh grace allowed with ip change, userId={}, prevIp={}, currentIp={}",
                    user.getId(), ctx.ipAddress(), ipAddress);
        }

        metrics.refreshGrace();
        // 重发同一最新 token：多标签页 Cookie 收敛为同一值，轮换代不变
        return buildResponse(user, latestToken);
    }

    /**
     * 签发令牌并落地会话。
     *
     * <p>做什么：生成 Access/Refresh 令牌并经 {@link UserSessionStore} 创建会话，
     * maxExpireAt = 创建时刻 + 绝对上限（FR-5：24 小时，轮换不延长）。
     */
    private AuthResponse issueTokens(User user, String device, String ipAddress) {
        Instant now = Instant.now();
        Instant maxExpireAt = now.plus(jwtProperties.getRefreshTokenTtl());
        Set<String> roles = rolesOf(user);
        String accessToken = jwtService.createAccessToken(user.getId(), user.getUsername(), roles);
        String refreshToken = jwtService.createRefreshToken(user.getId(), user.getUsername(), roles);

        sessionStore.create(new SessionCreate(
                user.getId(), refreshToken, device, ipAddress, now, maxExpireAt));

        long expiresIn = jwtProperties.getAccessTokenTtl().toSeconds();
        return new AuthResponse(accessToken, refreshToken, expiresIn, toResponse(user));
    }

    private AuthResponse buildResponse(User user, String refreshToken) {
        String accessToken = jwtService.createAccessToken(
                user.getId(), user.getUsername(), rolesOf(user));
        return new AuthResponse(accessToken, refreshToken,
                jwtProperties.getAccessTokenTtl().toSeconds(), toResponse(user));
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail());
    }

    private Set<String> rolesOf(User user) {
        return Set.of(user.getRole().name());
    }
}
