package com.wisread.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 认证行为配置。
 *
 * <p>对应需求 FR-6（登录哑哈希抹平时延）与 FR-8（刷新重放宽限窗口）。
 */
@ConfigurationProperties(prefix = "wisread.auth")
public class WisreadAuthProperties {

    /**
     * 登录时用户不存在分支使用的哑 BCrypt 哈希。
     * 作用：抹平“用户不存在（~1ms）”与“密码比对（~50-100ms）”的响应时延差，
     * 防止邮箱枚举侧信道（审计 M2）。
     * 建议配置为固定的 BCrypt 串（多实例一致）；未配置时启动阶段随机生成，
     * 时延特征一致（同 cost），不影响防枚举效果。
     */
    private String dummyPasswordHash;

    /**
     * PREVIOUS（上一代令牌）宽限判定窗口。
     * 旧令牌命中上一代哈希时，仅当轮换时间戳在窗口内才允许宽限，
     * 否则判定为重放并吊销该用户全部会话。默认 5 分钟。
     */
    private Duration refreshGraceWindow = Duration.ofMinutes(5);

    /**
     * 宽限判定是否严格要求 IP 一致。
     * 默认 false：移动网络 WiFi/4G 切换导致 IP 漂移属正常场景，仅记 WARN 不拦截。
     * 设为 true 时 IP 不一致即判重放。
     */
    private boolean refreshGraceStrictIp = false;

    /**
     * 是否接受无 typ 声明的旧版 refresh token（R1→R2 过渡兼容）。
     * access 侧已强制校验 typ=access，旧 refresh token 无法冒充 access，
     * 此兼容不引入安全风险；旧 token 下次刷新即被替换为带 typ 的新 token。
     * R2 稳定后置为 false。
     */
    private boolean legacyTypAccepted = true;

    public String getDummyPasswordHash() {
        return dummyPasswordHash;
    }

    public void setDummyPasswordHash(String dummyPasswordHash) {
        this.dummyPasswordHash = dummyPasswordHash;
    }

    public Duration getRefreshGraceWindow() {
        return refreshGraceWindow;
    }

    public void setRefreshGraceWindow(Duration refreshGraceWindow) {
        this.refreshGraceWindow = refreshGraceWindow;
    }

    public boolean isRefreshGraceStrictIp() {
        return refreshGraceStrictIp;
    }

    public void setRefreshGraceStrictIp(boolean refreshGraceStrictIp) {
        this.refreshGraceStrictIp = refreshGraceStrictIp;
    }

    public boolean isLegacyTypAccepted() {
        return legacyTypAccepted;
    }

    public void setLegacyTypAccepted(boolean legacyTypAccepted) {
        this.legacyTypAccepted = legacyTypAccepted;
    }
}
