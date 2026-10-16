package com.nexus.quota.web;

import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.GlobalExceptionHandler;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.exception.InvalidApplicationIdException;
import com.nexus.quota.controller.ApplicationQuotaController;
import com.nexus.quota.dto.QueryQuotaRequest;
import com.nexus.quota.dto.QueryQuotaResponse;
import com.nexus.quota.service.ApplicationQuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApplicationQuotaControllerTests {
    private final BearerUserIdResolver users = mock(BearerUserIdResolver.class);
    private final ApplicationQuotaService quotas = mock(ApplicationQuotaService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApplicationQuotaController(users, quotas))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void returnsQuotaFromServiceForAuthenticatedUser() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 10, 13, 14, 0);
        when(users.resolve("Bearer test-token")).thenReturn(42L);
        when(quotas.queryMyApplicationQuota(42L, new QueryQuotaRequest(7L)))
                .thenReturn(new QueryQuotaResponse(start, start.plusHours(720), 1000, 350, 650));
        mvc.perform(get("/api/application/7/quota").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.periodStart").value("2026-10-13T14:00:00"))
                .andExpect(jsonPath("$.data.periodEnd").value("2026-11-12T14:00:00"))
                .andExpect(jsonPath("$.data.quotaLimit").value(1000))
                .andExpect(jsonPath("$.data.usedCount").value(350))
                .andExpect(jsonPath("$.data.restCount").value(650));
        verify(quotas).queryMyApplicationQuota(42L, new QueryQuotaRequest(7L));
        verify(quotas, never()).consumeOnce(any());
    }

    @Test void missingTokenIs401WithoutQueryingQuota() throws Exception {
        when(users.resolve(null)).thenThrow(new InvalidAccessTokenException());
        mvc.perform(get("/api/application/7/quota"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
        verifyNoInteractions(quotas);
    }

    @Test void foreignOrMissingApplicationIs404() throws Exception {
        when(users.resolve("Bearer test-token")).thenReturn(42L);
        when(quotas.queryMyApplicationQuota(42L, new QueryQuotaRequest(7L)))
                .thenThrow(new ApplicationNotFoundException());
        mvc.perform(get("/api/application/7/quota").header("Authorization", "Bearer test-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
    }

    @Test void invalidApplicationIdUsesExisting400Contract() throws Exception {
        when(users.resolve("Bearer test-token")).thenReturn(42L);
        when(quotas.queryMyApplicationQuota(42L, new QueryQuotaRequest(0L)))
                .thenThrow(new InvalidApplicationIdException());
        mvc.perform(get("/api/application/0/quota").header("Authorization", "Bearer test-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPLICATION_ID"));
    }
}
