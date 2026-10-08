package com.nexus.usage;

import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.usage.controller.UsageEventController;
import com.nexus.usage.dto.TimeRange;
import com.nexus.usage.dto.UsageQueryRequest;
import com.nexus.usage.dto.UsageQueryResponse;
import com.nexus.usage.service.UsageQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UsageEventController.class)
class UsageControllerTests {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private UsageQueryService usageQueryService;
    @MockitoBean private BearerUserIdResolver bearerUserIdResolver;

    @Test
    void browserGetQueryWithoutBodyReturnsAggregates() throws Exception {
        UsageQueryRequest request = new UsageQueryRequest(11L, 1L, TimeRange.LAST_7_DAYS, null, null);
        UsageQueryResponse result = new UsageQueryResponse(12L, 2L, "Asia/Shanghai",
                OffsetDateTime.parse("2026-10-02T00:00:00+08:00"),
                OffsetDateTime.parse("2026-10-09T00:00:00+08:00"),
                List.of(new UsageQueryResponse.DailyCount(LocalDate.of(2026, 10, 8), 2)),
                List.of(new UsageQueryResponse.ApiCount("utils.hash", "哈希计算工具", 2)));
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(usageQueryService.queryMyUsageEvent(42L, request)).thenReturn(result);

        mockMvc.perform(get("/api/usage")
                        .header("Authorization", "Bearer valid-token")
                        .param("applicationId", "1")
                        .param("apiKeyId", "11")
                        .param("timeRange", "LAST_7_DAYS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allTimeCount").value(12))
                .andExpect(jsonPath("$.data.periodCount").value(2))
                .andExpect(jsonPath("$.data.dailyCounts[0].date").value("2026-10-08"))
                .andExpect(jsonPath("$.data.apiCounts[0].apiCode").value("utils.hash"));
        verify(usageQueryService).queryMyUsageEvent(42L, request);
    }

    @Test
    void customBeijingWallClockTimesBindFromQueryParameters() throws Exception {
        UsageQueryRequest request = new UsageQueryRequest(11L, 1L, TimeRange.CUSTOM,
                LocalDateTime.of(2026, 10, 8, 0, 0), LocalDateTime.of(2026, 10, 9, 0, 0));
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);

        mockMvc.perform(get("/api/usage")
                        .header("Authorization", "Bearer valid-token")
                        .param("applicationId", "1")
                        .param("apiKeyId", "11")
                        .param("timeRange", "CUSTOM")
                        .param("customStartTime", "2026-10-08T00:00:00")
                        .param("customEndTime", "2026-10-09T00:00:00"))
                .andExpect(status().isOk());
        verify(usageQueryService).queryMyUsageEvent(42L, request);
    }
}
