package com.wisread.controller;

import com.wisread.config.WisreadJwtProperties;
import com.wisread.dto.LoginRequest;
import com.wisread.dto.AuthResponse;
import com.wisread.dto.UserResponse;
import com.wisread.security.TokenBlacklistService;
import com.wisread.service.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        WisreadJwtProperties jwtProperties = new WisreadJwtProperties();
        jwtProperties.setRefreshTokenTtl(Duration.ofHours(24));
        controller = new AuthController(authService, tokenBlacklistService, jwtProperties);
    }

    @Test
    void loginSetsCookieWithTwentyFourHourMaxAge() throws Exception {
        // FR-5：Cookie Max-Age 与会话绝对有效期一致（86400 秒）
        when(authService.login(any(LoginRequest.class), any(), any()))
                .thenReturn(new AuthResponse("access", "refresh", 900L,
                        new UserResponse(1L, "alice", "alice@example.com")));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "Chrome");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<AuthResponse> result =
                controller.login(new LoginRequest("alice@example.com", "password123"), request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        Cookie cookie = response.getCookie(AuthController.REFRESH_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("refresh");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getMaxAge()).isEqualTo(86400);
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth/refresh");
    }

    @Test
    void logoutBlacklistsBothAccessAndRefreshTokens() throws Exception {
        // FR-2：登出同时吊销 access 与 refresh token
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer access-token");
        request.setCookies(new Cookie(AuthController.REFRESH_COOKIE, "refresh-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Void> result = controller.logout(request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(tokenBlacklistService).blacklist("access-token");
        verify(tokenBlacklistService).blacklist("refresh-token");
        verify(authService).logout("refresh-token");
        // Cookie 被清除
        assertThat(response.getCookie(AuthController.REFRESH_COOKIE).getMaxAge()).isEqualTo(0);
    }

    @Test
    void logoutIsIdempotentWithoutTokens() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<Void> result = controller.logout(request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(tokenBlacklistService, never()).blacklist(any());
        verify(authService).logout(isNull());
    }

    @Test
    void refreshReturnsUnauthorizedWhenCookieMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<AuthResponse> result = controller.refresh(request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(authService, never()).refresh(any(), any(), any());
    }

    @Test
    void refreshForwardsGraceTokenToCookie() throws Exception {
        // FR-8：宽限路径重发的同一最新 token 通过 Set-Cookie 下发（响应体不含 refresh）
        when(authService.refresh(eq("latest-token"), any(), any()))
                .thenReturn(new AuthResponse("access", "latest-token", 900L,
                        new UserResponse(1L, "alice", "alice@example.com")));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthController.REFRESH_COOKIE, "latest-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<AuthResponse> result = controller.refresh(request, response);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        Cookie cookie = response.getCookie(AuthController.REFRESH_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("latest-token");
    }
}
