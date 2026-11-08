package com.nexus.usage;

import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.usage.dto.TimeRange;
import com.nexus.usage.dto.UsageQueryRequest;
import com.nexus.usage.mapper.UsageEventMapper;
import com.nexus.usage.metrics.UsageRecordMetrics;
import com.nexus.usage.service.UsageQueryService;
import com.nexus.usage.service.UsageRecordService;
import com.nexus.usage.web.UsageInterceptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UsageClockTests {
    @ParameterizedTest
    @CsvSource({"2026-11-08T15:59:59.999Z,2026-11-08", "2026-11-08T16:00:00Z,2026-11-09"})
    void todayChangesAtBeijingMidnightWithClockInAnotherZone(String now, String day) {
        UsageEventMapper events = mock(UsageEventMapper.class);
        ApplicationMapper apps = mock(ApplicationMapper.class);
        ApiKeyMapper keys = mock(ApiKeyMapper.class);
        when(apps.selectOne(any())).thenReturn(new Application());
        ApiKey key = new ApiKey();
        key.setApplicationId(1L);
        when(keys.selectOne(any())).thenReturn(key);
        Clock clock = Clock.fixed(Instant.parse(now), ZoneId.of("America/Los_Angeles"));
        var response = new UsageQueryService(events, apps, keys, clock).queryMyUsageEvent(42L,
                new UsageQueryRequest(11L, 1L, TimeRange.TODAY, null, null));
        LocalDate date = LocalDate.parse(day);
        assertEquals(date.atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(8)), response.periodStart());
        assertEquals("Asia/Shanghai", response.timeZone());
        verify(events).selectDailyCounts(11L, 1L, date.atStartOfDay().minusHours(8),
                date.plusDays(1).atStartOfDay().minusHours(8));
    }

    @Test
    void requestTimeUsesUtcMillisecondsInsteadOfClockZone() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-08T20:30:00.123456789Z"), ZoneId.of("Asia/Shanghai"));
        UsageInterceptor interceptor = new UsageInterceptor(mock(UsageRecordService.class),
                new UsageRecordMetrics(new SimpleMeterRegistry()), clock);
        MockHttpServletRequest request = new MockHttpServletRequest();
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        assertEquals(LocalDateTime.parse("2026-11-08T20:30:00.123"), request.getAttribute("usage.startUtcTime"));
    }
}
