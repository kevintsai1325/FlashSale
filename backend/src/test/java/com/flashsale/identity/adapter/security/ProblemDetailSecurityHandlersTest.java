package com.flashsale.identity.adapter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailSecurityHandlersTest {

    @Test
    void authenticationProblemDetailIsWrittenAsUtf8() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProblemDetailAuthenticationEntryPoint(new ObjectMapper()).commence(
            request, response, new InsufficientAuthenticationException("missing"));

        assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("存取此資源需要先登入");
    }

    @Test
    void accessDeniedProblemDetailIsWrittenAsUtf8() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProblemDetailAccessDeniedHandler(new ObjectMapper()).handle(
            request, response, new AccessDeniedException("denied"));

        assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("您沒有權限存取此資源");
    }
}
