package com.nexus.auth.controller;

import com.nexus.auth.dto.AuthTokensResponse;
import com.nexus.auth.dto.RefreshRequest;
import com.nexus.auth.exception.AccountForbiddenException;
import com.nexus.auth.exception.AuthSessionUnavailableException;
import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.exception.InvalidRefreshTokenException;
import com.nexus.auth.exception.RefreshTokenReplayException;
import com.nexus.auth.token.IssuedAccessToken;
import com.nexus.common.web.ApiResponse;
import com.nexus.common.web.GlobalExceptionHandler;
import jakarta.validation.Valid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 只验证 DTO/HTTP 错误契约；探针 Controller 仅存在于测试，不验证真实会话或数据库行为。 */
class AuthSessionHttpContractTests {
    private static final String REFRESH_BODY = "{\"refreshToken\":\"test-only-refresh-token\"}";
    private final ContractController controller = new ContractController();
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void refreshShouldAcceptOnlyTheRefreshTokenAndSerializeTheFullTokenResponse() throws Exception {
        refresh(REFRESH_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("刷新成功"))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").value("test-only-access-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600))
                .andExpect(jsonPath("$.data.refreshToken").value("test-only-refresh-token"))
                .andExpect(jsonPath("$.data.sessionId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.sessionExpiresAt").value("2026-11-16T01:00:00Z"));

        assertEquals(new RefreshRequest("test-only-refresh-token"), controller.lastRefreshRequest);
    }

    @Test
    void missingNullOrBlankRefreshTokensShouldFailValidationBeforeControllerExecution() throws Exception {
        for (String body : new String[]{"{}", "{\"refreshToken\":null}", "{\"refreshToken\":\"  \"}"}) {
            refresh(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.data.refreshToken").value("刷新凭据不能为空"));
        }
        assertNull(controller.lastRefreshRequest);
    }

    @Test
    void malformedJsonShouldReturnAnErrorWithoutEchoingTheCredential() throws Exception {
        String body = refresh("{\"refreshToken\":\"test-only-refresh-token\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("test-only-refresh-token"));
        assertNull(controller.lastRefreshRequest);
    }

    @Test
    void invalidRefreshCredentialsShouldReturnUnauthorized() throws Exception {
        assertRefreshFailure(new InvalidRefreshTokenException(), 401, "INVALID_REFRESH_TOKEN");
    }

    @Test
    void confirmedReplayShouldHaveADistinctUnauthorizedCode() throws Exception {
        assertRefreshFailure(new RefreshTokenReplayException(), 401, "REFRESH_TOKEN_REPLAYED");
    }

    @Test
    void unavailableSessionOperationsShouldNotExposeDatabaseDiagnostics() throws Exception {
        String privateDiagnostic = "test-only-internal-database-detail";
        controller.failure = new AuthSessionUnavailableException(new IllegalStateException(privateDiagnostic));

        String body = refresh(REFRESH_BODY)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("登录会话服务暂不可用"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(privateDiagnostic));
        assertFalse(body.contains("test-only-refresh-token"));
    }

    @Test
    void forbiddenAccountsShouldReuseTheExistingForbiddenContract() throws Exception {
        assertRefreshFailure(new AccountForbiddenException(), 403, "AUTH_ACCOUNT_FORBIDDEN");
    }

    @Test
    void invalidLogoutAccessTokensShouldReuseTheExistingUnauthorizedContract() throws Exception {
        controller.failure = new InvalidAccessTokenException();
        mvc.perform(post("/__test/auth/logout").header("Authorization", "Bearer test-only-access-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void voidSuccessShouldUseTheExistingResponseEnvelope() throws Exception {
        mvc.perform(post("/__test/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    private ResultActions refresh(String body) throws Exception {
        return mvc.perform(post("/__test/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void assertRefreshFailure(RuntimeException failure, int httpStatus, String code) throws Exception {
        controller.failure = failure;
        refresh(REFRESH_BODY)
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @RestController
    static class ContractController {
        private RuntimeException failure;
        private RefreshRequest lastRefreshRequest;

        @PostMapping("/__test/auth/refresh")
        public ApiResponse<AuthTokensResponse> refresh(@Valid @RequestBody RefreshRequest request) {
            lastRefreshRequest = request;
            if (failure != null) {
                throw failure;
            }
            return new ApiResponse<>("SUCCESS", "刷新成功", AuthTokensResponse.from(
                    new IssuedAccessToken("test-only-access-token", 600),
                    "test-only-refresh-token", "9007199254740993", Instant.parse("2026-11-16T01:00:00Z")));
        }

        @PostMapping("/__test/auth/logout")
        public ApiResponse<Void> logout() {
            if (failure != null) {
                throw failure;
            }
            return new ApiResponse<>("SUCCESS", "退出成功", null);
        }
    }
}
