package com.nexus.common.web;

import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.shortlink.exception.ShortCodeExhaustedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerLoggingTests {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailureController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new RequestFailureLoggingFilter())
            .build();

    @Test
    void handled401IsLoggedOnlyByAdviceWithoutLeakingAuthorization(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/test-logging/invalid-token")
                        .header("Authorization", "Bearer never-log-this-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        assertEquals(1, occurrences(output.getOut(), "请求被拒绝"));
        assertTrue(output.getOut().contains("code=INVALID_ACCESS_TOKEN"));
        assertFalse(output.getOut().contains("never-log-this-token"));
    }

    @Test
    void handled503KeepsExistingResponseAndLogsOneError(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/test-logging/no-short-code"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SHORT_CODE_UNAVAILABLE"));

        assertEquals(1, occurrences(output.getOut(), "请求处理失败"));
        assertEquals(0, occurrences(output.getOut(), "请求失败 method="));
    }

    private static int occurrences(String text, String fragment) {
        return text.split(fragment, -1).length - 1;
    }

    @RestController
    static class FailureController {
        @GetMapping("/api/test-logging/invalid-token")
        void invalidToken() {
            throw new InvalidAccessTokenException();
        }

        @GetMapping("/api/test-logging/no-short-code")
        void noShortCode() {
            throw new ShortCodeExhaustedException();
        }
    }
}
