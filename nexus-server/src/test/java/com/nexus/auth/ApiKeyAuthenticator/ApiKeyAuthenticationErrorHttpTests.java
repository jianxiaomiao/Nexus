package com.nexus.auth.ApiKeyAuthenticator;

import com.nexus.auth.exception.ApiKeyForbiddenException;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import com.nexus.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiKeyAuthenticationErrorHttpTests {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ErrorController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void invalidCredentialReturns401AndChallenge() throws Exception {
        mvc.perform(get("/test/machine-auth/invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "ApiKey realm=\"nexus-openapi\""))
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY_CREDENTIAL"));
    }

    @Test
    void disabledCredentialReturns403() throws Exception {
        mvc.perform(get("/test/machine-auth/disabled"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("API_KEY_FORBIDDEN"));
    }

    @RestController
    static class ErrorController {
        @GetMapping("/test/machine-auth/invalid")
        void invalid() { throw new InvalidApiKeyCredentialException(); }

        @GetMapping("/test/machine-auth/disabled")
        void disabled() { throw new ApiKeyForbiddenException(); }
    }
}
