package com.nexus.common.web;

import jakarta.servlet.http.HttpServletResponse;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyAuthenticator;
import com.nexus.openapi.web.MachineApiKeyFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class RequestFailureLoggingFilterTests {
    private final RequestFailureLoggingFilter filter = new RequestFailureLoggingFilter();

    @Test
    void successfulResponseIsNotLogged(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/application");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(200));

        assertFalse(output.getOut().contains("请求被拒绝"));
        assertFalse(output.getOut().contains("请求失败"));
    }

    @Test
    void returnedAuthenticationFailureIsLoggedOnceWithoutCredential(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/utils/uuid");
        request.addHeader("Authorization", "ApiKey never-log-this-credential");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(401));

        assertEquals(401, response.getStatus());
        assertEquals(1, occurrences(output.getOut(), "请求被拒绝"));
        assertFalse(output.getOut().contains("never-log-this-credential"));
    }

    @Test
    void machineFilterShortCircuitIsStillLoggedByOuterFilter(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/utils/uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        MachineApiKeyFilter machineFilter = new MachineApiKeyFilter(mock(ApiKeyAuthenticator.class), objectMapper);

        filter.doFilter(request, response, (req, res) ->
                machineFilter.doFilter(req, res, (ignoredRequest, ignoredResponse) ->
                        fail("认证失败后不应到达 Controller")));

        assertEquals(401, response.getStatus());
        assertEquals(1, occurrences(output.getOut(), "请求被拒绝"));
    }

    @Test
    void adviceLoggedFailureIsNotLoggedAgain(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/application");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            req.setAttribute(RequestFailureLoggingFilter.HANDLED_EXCEPTION_LOGGED, Boolean.TRUE);
            ((HttpServletResponse) res).setStatus(404);
        });

        assertEquals(404, response.getStatus());
        assertFalse(output.getOut().contains("请求被拒绝"));
    }

    @Test
    void returnedServerFailureIsLoggedAsError(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/application");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(500));

        assertEquals(1, occurrences(output.getOut(), "请求失败 method="));
        assertTrue(output.getOut().contains("status=500"));
    }

    @Test
    void escapingExceptionIsLoggedAndRethrownEvenWhenStatusIsStill200(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/application");
        MockHttpServletResponse response = new MockHttpServletResponse();
        IOException failure = new IOException("downstream failed");

        IOException thrown = assertThrows(IOException.class,
                () -> filter.doFilter(request, response, (req, res) -> { throw failure; }));

        assertSame(failure, thrown);
        assertEquals(200, response.getStatus());
        assertEquals(1, occurrences(output.getOut(), "请求处理抛异常"));
        assertFalse(output.getOut().contains("请求被拒绝"));
    }

    @Test
    void loggingFilterIsRegisteredForBothApiKinds() {
        var registration = new RequestFailureLoggingFilterConfig().requestFailureLoggingFilter();

        assertEquals(10, registration.getOrder());
        assertTrue(registration.getUrlPatterns().contains("/api/*"));
        assertTrue(registration.getUrlPatterns().contains("/v1/*"));
    }

    private static int occurrences(String text, String fragment) {
        return text.split(fragment, -1).length - 1;
    }
}
