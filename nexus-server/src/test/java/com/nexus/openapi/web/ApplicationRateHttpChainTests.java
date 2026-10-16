package com.nexus.openapi.web;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyAuthenticator;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import com.nexus.common.web.ApiResponse;
import com.nexus.common.web.RequestFailureLoggingFilter;
import com.nexus.openapi.controller.UuidOpenApiController;
import com.nexus.openapi.ratelimit.ApplicationRateLimiter;
import com.nexus.openapi.ratelimit.QueueDecision;
import com.nexus.openapi.ratelimit.RateLimitAdmission;
import com.nexus.quota.service.ApplicationQuotaService;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(OutputCaptureExtension.class)
class ApplicationRateHttpChainTests {

    @Test
    void authenticationFailureStopsBeforeRateLimitAndQuota(CapturedOutput output)
            throws Exception {
        Fixture fixture = fixture();
        when(fixture.authenticator().authenticate("test-key"))
                .thenThrow(new InvalidApiKeyCredentialException());

        fixture.mvc().perform(machineGet())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "ApiKey realm=\"nexus-openapi\""))
                .andExpect(jsonPath("$.code")
                        .value("INVALID_API_KEY_CREDENTIAL"));

        verifyNoInteractions(fixture.limiter(), fixture.quotaService());
        assertEquals(1, occurrences(output.getOut(), "请求被拒绝"));
    }

    @Test
    void immediateAllowanceConsumesQuotaAndInvokesControllerOnce()
            throws Exception {
        Fixture fixture = fixture();
        when(fixture.limiter().acquire(20L))
                .thenReturn(new RateLimitAdmission.Allowed());

        fixture.mvc().perform(machineGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.uuid").isNotEmpty());

        verify(fixture.authenticator(), times(1)).authenticate("test-key");
        verify(fixture.limiter(), times(1)).acquire(20L);
        verify(fixture.quotaService(), times(1))
                .consumeOnce(fixture.identity());
    }

    @Test
    void queuedAllowanceDispatchesWithoutReauthenticationOrReadmission()
            throws Exception {
        Fixture fixture = fixture();
        CompletableFuture<QueueDecision> completion = new CompletableFuture<>();
        RateLimitAdmission.WaitHandle handle = waitHandle(completion);
        when(fixture.limiter().acquire(20L))
                .thenReturn(new RateLimitAdmission.Queued(handle));

        MvcResult pending = fixture.mvc().perform(machineGet())
                .andExpect(request().asyncStarted())
                .andReturn();
        verifyNoInteractions(fixture.quotaService());

        completion.complete(QueueDecision.ALLOWED);

        fixture.mvc().perform(servletAsyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        verify(fixture.authenticator(), times(1)).authenticate("test-key");
        verify(fixture.limiter(), times(1)).acquire(20L);
        verify(fixture.quotaService(), times(1))
                .consumeOnce(fixture.identity());
    }

    @Test
    void applicationQueueFullReturns429WithoutQuota(CapturedOutput output)
            throws Exception {
        Fixture fixture = fixture();
        when(fixture.limiter().acquire(20L)).thenReturn(
                new RateLimitAdmission.Rejected(
                        RateLimitAdmission.RejectReason.APPLICATION_QUEUE_FULL));

        fixture.mvc().perform(machineGet())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code")
                        .value("APPLICATION_RATE_QUEUE_FULL"));

        verifyNoInteractions(fixture.quotaService());
        assertEquals(1, occurrences(output.getOut(), "请求被拒绝"));
    }

    @Test
    void queuedTimeoutReturns429WithoutQuotaAndLogsFinalStatus(
            CapturedOutput output) throws Exception {
        Fixture fixture = fixture();
        CompletableFuture<QueueDecision> completion = new CompletableFuture<>();
        RateLimitAdmission.WaitHandle handle = waitHandle(completion);
        when(fixture.limiter().acquire(20L)).thenReturn(
                new RateLimitAdmission.Queued(handle));

        MvcResult pending = fixture.mvc().perform(machineGet())
                .andExpect(request().asyncStarted())
                .andReturn();
        assertFalse(output.getOut().contains("status=429"));

        completion.complete(QueueDecision.TIMED_OUT);

        fixture.mvc().perform(servletAsyncDispatch(pending))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code")
                        .value("APPLICATION_RATE_WAIT_TIMEOUT"));

        verifyNoInteractions(fixture.quotaService());
        verify(fixture.limiter(), times(1)).acquire(20L);
        assertEquals(1, occurrences(output.getOut(), "status=429"));
    }

    @Test
    void infrastructureRejectionsReturn503WithoutQuota() throws Exception {
        assertInfrastructureRejection(
                RateLimitAdmission.RejectReason.GLOBAL_QUEUE_FULL,
                "RATE_LIMIT_GLOBAL_QUEUE_FULL");
        assertInfrastructureRejection(
                RateLimitAdmission.RejectReason.STATE_CAPACITY_REACHED,
                "RATE_LIMIT_STATE_CAPACITY_REACHED");
        assertInfrastructureRejection(
                RateLimitAdmission.RejectReason.LIMITER_UNAVAILABLE,
                "RATE_LIMITER_UNAVAILABLE");
    }

    private void assertInfrastructureRejection(
            RateLimitAdmission.RejectReason reason,
            String expectedCode) throws Exception {
        Fixture fixture = fixture();
        when(fixture.limiter().acquire(20L))
                .thenReturn(new RateLimitAdmission.Rejected(reason));

        fixture.mvc().perform(machineGet())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(expectedCode));

        verifyNoInteractions(fixture.quotaService());
    }

    private static RateLimitAdmission.WaitHandle waitHandle(
            CompletableFuture<QueueDecision> completion) {
        RateLimitAdmission.WaitHandle handle =
                mock(RateLimitAdmission.WaitHandle.class);
        when(handle.completion()).thenReturn(completion);
        return handle;
    }

    private static MockHttpServletRequestBuilder machineGet() {
        return get("/v1/utils/uuid")
                .header(HttpHeaders.AUTHORIZATION, "ApiKey test-key")
                .with(request -> {
                    request.setAsyncSupported(true);
                    return request;
                });
    }

    /**
     * {@code MockMvcRequestBuilders.asyncDispatch(...)} waits for Spring MVC's
     * {@code WebAsyncManager}. This filter uses the lower-level Servlet
     * {@code AsyncContext}, so reproduce the container's ASYNC redispatch
     * without consulting Spring MVC's async-result latch.
     */
    private static RequestBuilder servletAsyncDispatch(MvcResult result) {
        return servletContext -> {
            MockHttpServletRequest request = result.getRequest();
            MockAsyncContext firstCycle =
                    (MockAsyncContext) request.getAsyncContext();
            MockAsyncContext redispatchCycle = new MockAsyncContext(
                    request, result.getResponse());
            firstCycle.getListeners().forEach(redispatchCycle::addListener);
            redispatchCycle.setTimeout(firstCycle.getTimeout());
            request.setAsyncContext(redispatchCycle);
            request.setDispatcherType(DispatcherType.ASYNC);
            request.setAsyncStarted(false);
            return request;
        };
    }

    private static Fixture fixture() throws Exception {
        ApiKeyIdentity identity = new ApiKeyIdentity(10L, 20L);
        ApiKeyAuthenticator authenticator = mock(ApiKeyAuthenticator.class);
        when(authenticator.authenticate("test-key")).thenReturn(identity);
        ApplicationRateLimiter limiter = mock(ApplicationRateLimiter.class);
        ApplicationQuotaService quotaService = mock(ApplicationQuotaService.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenAnswer(invocation -> {
            ApiResponse<?> response = invocation.getArgument(0);
            return "{\"code\":\"" + response.code()
                    + "\",\"message\":\"" + response.message()
                    + "\",\"data\":null}";
        });

        MachineIdentityResolver identityResolver = new MachineIdentityResolver();
        UuidOpenApiController controller =
                new UuidOpenApiController(identityResolver, quotaService);
        MockMvc mvc = standaloneSetup(controller)
                .addFilters(
                        new RequestFailureLoggingFilter(),
                        new MachineApiKeyFilter(authenticator, objectMapper),
                        new ApplicationRateFilter(
                                limiter, identityResolver, objectMapper))
                .build();

        return new Fixture(
                mvc,
                authenticator,
                limiter,
                quotaService,
                identity);
    }

    private static int occurrences(String text, String fragment) {
        return text.split(fragment, -1).length - 1;
    }

    private record Fixture(
            MockMvc mvc,
            ApiKeyAuthenticator authenticator,
            ApplicationRateLimiter limiter,
            ApplicationQuotaService quotaService,
            ApiKeyIdentity identity) {
    }
}
