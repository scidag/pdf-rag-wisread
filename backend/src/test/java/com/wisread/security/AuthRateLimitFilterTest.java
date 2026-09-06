package com.wisread.security;

import com.wisread.config.WisreadRateLimitProperties;
import com.wisread.config.WisreadTrustProxyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthRateLimitFilterTest {

    @Mock
    private AuthRateLimiter rateLimiter;

    private WisreadRateLimitProperties properties;
    private WisreadTrustProxyProperties trustProxyProperties;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new WisreadRateLimitProperties();
        properties.setAuthPerMinute(10);
        properties.setRefreshPerMinute(30);
        trustProxyProperties = new WisreadTrustProxyProperties();
        filter = new AuthRateLimitFilter(rateLimiter, properties, trustProxyProperties);
    }

    @Test
    void passesWhenWithinLimit() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);

        MockHttpServletResponse response = call("POST", "/api/v1/auth/login", "127.0.0.1");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void blocksLoginWhenOverLimit() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(false);

        MockHttpServletResponse response = call("POST", "/api/v1/auth/login", "127.0.0.1");

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("too many requests");
    }

    @Test
    void appliesToRefreshEndpoint() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(false);

        MockHttpServletResponse response = call("POST", "/api/v1/auth/refresh", "127.0.0.1");

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void ignoresNonAuthEndpoints() throws Exception {
        // 非认证接口不限流
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void forgedForwardedForIgnoredWhenTrustProxyOff() throws Exception {
        // 审计 M1：trust-proxy=false 时伪造 X-Forwarded-For 不影响限流主体（仍按 TCP 对端计数）
        org.mockito.Mockito.reset(rateLimiter);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    return key.endsWith("127.0.0.1");
                });

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/login");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void forwardedForUsedWhenTrustProxyOn() throws Exception {
        trustProxyProperties.setTrustProxy(true);
        org.mockito.Mockito.reset(rateLimiter);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(0);
                    return key.endsWith("1.2.3.4");
                });

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse call(String method, String uri, String remoteAddr) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(uri);
        request.setRemoteAddr(remoteAddr);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
