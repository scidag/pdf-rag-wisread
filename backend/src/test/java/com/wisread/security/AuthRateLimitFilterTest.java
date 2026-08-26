package com.wisread.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitFilterTest {

    private final AuthRateLimitFilter filter = new AuthRateLimitFilter();

    @Test
    void blocksLoginAfterTenAttemptsInWindow() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = callLogin();
            assertThat(response.getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse blocked = callLogin();
        assertThat(blocked.getStatus()).isEqualTo(429);
    }

    private MockHttpServletResponse callLogin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/v1/auth/login");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
