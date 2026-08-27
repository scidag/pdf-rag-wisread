package com.wisread.service;

import com.wisread.dto.AuthResponse;
import com.wisread.dto.LoginRequest;
import com.wisread.dto.RegisterRequest;
import com.wisread.dto.UserResponse;

/**
 * 认证服务接口（AuthService）。
 *
 * <p>负责“智阅”系统的用户身份与登录态管理，是系统安全边界的入口。核心职责：
 * <ul>
 *   <li>用户注册：用户名/邮箱唯一性校验 + 密码 BCrypt 哈希落库。</li>
 *   <li>用户登录：凭据校验 + 账号状态校验，成功后签发双令牌（Access + Refresh）。</li>
 *   <li>令牌刷新：基于 Refresh Token 轮换（rotate）签发新令牌，并具备“重放检测”安全机制。</li>
 *   <li>登出：将 Refresh Token 对应的会话从库表吊销（黑名单）。</li>
 *   <li>当前用户：根据登录用户 ID 返回基础资料。</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>采用 Access Token（短期）+ Refresh Token（长期）双令牌机制。</li>
 *   <li>Refresh Token 在服务端仅以 SHA-256 哈希存储，不在数据库保存明文，降低泄露风险。</li>
 *   <li>每次刷新都会轮换 Refresh Token 并把上一代哈希留存，用于检测被泄露的旧令牌重复使用。</li>
 * </ul>
 */
public interface AuthService {

    /**
     * 用户注册。
     *
     * <p>做什么：校验用户名与邮箱的唯一性，对密码进行 BCrypt 加密后写入用户表，
     * 并立即为该用户签发一组全新的 Access/Refresh 令牌（注册即登录）。
     *
     * <p>业务约束：用户名与邮箱均不可重复（冲突返回 409）；密码以哈希存储，永不落库明文。
     *
     * @param request 注册请求，包含用户名、邮箱、明文密码
     * @return 包含新签发的令牌与用户信息的认证响应
     */
    AuthResponse register(RegisterRequest request);

    /**
     * 用户登录。
     *
     * <p>做什么：按邮箱查找用户，校验密码与账号状态，全部通过后签发双令牌并记录登录设备/IP。
     *
     * <p>业务约束：凭据错误统一返回 401（“invalid credentials”以避免泄露具体错误项）；
     * 账号状态不等于 1（正常）时拒绝登录（403/401 “account disabled”）。
     *
     * @param request   登录请求，含邮箱与密码
     * @param device    登录设备标识，用于会话记录
     * @param ipAddress 登录来源 IP，用于安全审计与会话追踪
     * @return 包含令牌与用户信息的认证响应
     */
    AuthResponse login(LoginRequest request, String device, String ipAddress);

    /**
     * 刷新访问令牌。
     *
     * <p>做什么：校验 Refresh Token 的有效性（存在且未过期），通过则轮换签发新的
     * Access/Refresh 令牌，并更新会话记录。
     *
     * <p>业务约束与安全防护：
     * <ul>
     *   <li>Refresh Token 以哈希比对，且必须处于有效期内。</li>
     *   <li>若提交的令牌命中“上一代哈希”（说明是已被轮换掉的旧令牌），判定为“令牌重放/泄露”，
     *       立即吊销该用户所有会话（删除全部 UserSession）。</li>
     * </ul>
     *
     * @param refreshToken 客户端持有的 Refresh Token
     * @param device       当前设备标识，刷新后更新会话记录
     * @param ipAddress    当前 IP，刷新后更新会话记录
     * @return 包含新令牌的认证响应
     */
    AuthResponse refresh(String refreshToken, String device, String ipAddress);

    /**
     * 登出（吊销会话）。
     *
     * <p>做什么：根据 Refresh Token 哈希定位会话并删除，使其后续无法再用于刷新令牌，
     * 相当于把该 Refresh Token 加入“黑名单”。
     *
     * @param refreshToken 待吊销的 Refresh Token，为空则直接忽略
     */
    void logout(String refreshToken);

    /**
     * 获取当前登录用户的基础信息。
     *
     * @param userId 当前登录用户 ID
     * @return 用户资料响应
     */
    UserResponse getCurrentUser(Long userId);
}
