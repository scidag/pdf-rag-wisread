package com.wisread.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可信代理配置（FR-9 / 审计 M1）。
 *
 * <p>默认 false：限流等场景直接取 TCP 对端地址，客户端伪造 X-Forwarded-For 无效。
 * 仅当服务部署于可信反向代理之后且代理正确设置 X-Forwarded-For 时才置为 true。
 */
@ConfigurationProperties(prefix = "wisread")
public class WisreadTrustProxyProperties {

    /** 是否信任 X-Forwarded-For 头作为客户端真实 IP。 */
    private boolean trustProxy = false;

    public boolean isTrustProxy() {
        return trustProxy;
    }

    public void setTrustProxy(boolean trustProxy) {
        this.trustProxy = trustProxy;
    }
}
