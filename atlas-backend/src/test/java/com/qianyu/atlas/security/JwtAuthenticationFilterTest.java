package com.qianyu.atlas.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtAuthenticationFilterTest {
    private static final String SECRET = "atlas-test-secret-key-at-least-32-bytes-long";

    @Test
    void invalidBearerTokenReturnsUnauthorizedImmediately() throws ServletException, IOException {
        JwtService jwtService = new JwtService(SECRET, 24, new MockEnvironment());
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/notes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        request.addHeader("Authorization", "Bearer not-a-valid-token");

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("登录已过期"));
    }
}
