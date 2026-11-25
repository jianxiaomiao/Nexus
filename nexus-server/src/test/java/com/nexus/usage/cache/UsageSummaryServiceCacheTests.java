package com.nexus.usage.cache;

import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.exception.ApiKeyNotFoundException;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.usage.dto.TimeRange;
import com.nexus.usage.dto.UsageQueryRequest;
import com.nexus.usage.dto.UsageQueryResponse;
import com.nexus.usage.mapper.UsageEventMapper;
import com.nexus.usage.service.UsageQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UsageSummaryServiceCacheTests {
    private static final Instant NOW = Instant.parse("2026-11-24T16:00:01Z");
    private static final UsageQueryRequest TODAY = new UsageQueryRequest(3L, 2L, TimeRange.TODAY, null, null);

    private final ApplicationMapper applications = mock(ApplicationMapper.class);
    private final ApiKeyMapper keys = mock(ApiKeyMapper.class);
    private final UsageEventMapper events = mock(UsageEventMapper.class);
    private final UsageSummaryRedisCache cache = mock(UsageSummaryRedisCache.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transaction = mock(TransactionStatus.class);
    private final UsageQueryService service = new UsageQueryService(events, applications, keys,
            Clock.fixed(NOW, ZoneOffset.UTC), Optional.of(cache), manager);

    @BeforeEach
    void transactionCallbacksCanRun() {
        when(manager.getTransaction(any())).thenReturn(transaction);
    }

    @Test
    void cacheHitStillAuthorizesAndUsesResolvedBeijingDay() {
        ownedKey();
        var key = new UsageSummaryRedisCache.Key(1, 2, 3,
                LocalDateTime.parse("2026-11-24T16:00:00"), LocalDateTime.parse("2026-11-25T16:00:00"));
        var cached = mock(UsageQueryResponse.class);
        when(cache.getOrLoad(eq(key), eq(NOW), any())).thenReturn(cached);

        assertSame(cached, service.queryMyUsageEvent(1L, TODAY));

        var order = inOrder(applications, keys, manager, cache);
        order.verify(manager).getTransaction(any());
        order.verify(applications).selectOne(any());
        order.verify(keys).selectOne(any());
        order.verify(manager).commit(transaction);
        order.verify(cache).getOrLoad(eq(key), eq(NOW), any());
        verifyNoInteractions(events);
    }

    @Test
    void rejectsOtherOwnersAndDeletedKeysBeforeCacheLookup() {
        assertThrows(ApplicationNotFoundException.class, () -> service.queryMyUsageEvent(7L, TODAY));
        var application = new Application();
        application.setId(2L);
        when(applications.selectOne(any())).thenReturn(application);
        assertThrows(ApiKeyNotFoundException.class, () -> service.queryMyUsageEvent(1L, TODAY));
        verifyNoInteractions(cache, events);
    }

    @Test
    void customRangesRemainLive() {
        ownedKey();
        var start = LocalDateTime.parse("2026-11-21T03:00:00");
        var end = start.plusHours(2);
        var request = new UsageQueryRequest(3L, 2L, TimeRange.CUSTOM, start, end);

        assertEquals(0, service.queryMyUsageEvent(1L, request).periodCount());

        verify(events).selectCount(any());
        verify(events).selectDailyCounts(3L, 2L, start.minusHours(8), end.minusHours(8));
        verify(events).selectApiCounts(3L, 2L, start.minusHours(8), end.minusHours(8));
        verifyNoInteractions(cache);
    }

    private void ownedKey() {
        var application = new Application();
        application.setId(2L);
        when(applications.selectOne(any())).thenReturn(application);
        when(keys.selectOne(any())).thenReturn(new ApiKey());
    }
}
