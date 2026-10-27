package com.nexus.openapi.web;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.openapi.ratelimit.ApplicationRateLimiter;
import com.nexus.openapi.ratelimit.ApplicationRateMetrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApplicationRateFilterExceptionTests {

    @Test
    void invalidRateLimitIdentityIsTranslatedAtTheFilterBoundary() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ApplicationRateLimiter limiter = new ApplicationRateLimiter(
                scheduler, 10, 100, 100,
                Duration.ofMillis(100), Duration.ofSeconds(1), System::nanoTime);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any()))
                .thenReturn("{\"code\":\"RATE_LIMIT_IDENTITY_INVALID\",\"message\":\"限流身份中的 Application ID 必须为正数\",\"data\":null}");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ApplicationRateFilter filter = new ApplicationRateFilter(
                limiter, new MachineIdentityResolver(), objectMapper,
                new ApplicationRateMetrics(meterRegistry));
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/v1/utils/uuid");
        request.setAttribute(MachineIdentityResolver.ATTRIBUTE_NAME,
                new ApiKeyIdentity(1L, 0L));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(500, response.getStatus());
        assertTrue(response.getContentAsString().contains("RATE_LIMIT_IDENTITY_INVALID"));
        verifyNoInteractions(chain);
        Timer unavailable = meterRegistry.find(
                        ApplicationRateMetrics.ADMISSION_METRIC_NAME)
                .tag("outcome", "unavailable")
                .timer();
        assertEquals(1.0, unavailable.count());
    }
}
