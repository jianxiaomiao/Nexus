package com.nexus.quota.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.exception.InvalidApplicationIdException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import com.nexus.quota.dto.QueryQuotaRequest;
import com.nexus.quota.entity.ApplicationQuota;
import com.nexus.quota.mapper.ApplicationQuotaMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApplicationQuotaServiceTests {
    private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 10, 13, 14, 0, 0, 123_000_000);
    private static final Instant ANCHOR_INSTANT = ANCHOR.toInstant(ZoneOffset.ofHours(8));
    private final ApplicationMapper applications = mock(ApplicationMapper.class);
    private final ApiKeyMapper keys = mock(ApiKeyMapper.class);
    private final ApplicationQuotaMapper quotas = mock(ApplicationQuotaMapper.class);
    private final ApplicationQuotaService service = new ApplicationQuotaService(applications, keys, quotas,
            Clock.fixed(ANCHOR_INSTANT.plusSeconds(1), ZoneOffset.UTC), mock(PlatformTransactionManager.class));

    @Test void firstInstantBelongsToFirstPeriod() {
        assertEquals(ANCHOR, ApplicationQuotaService.currentPeriodStart(ANCHOR, ANCHOR_INSTANT));
    }

    @Test void lastNanosecondStillBelongsToOldPeriod() {
        assertEquals(ANCHOR, ApplicationQuotaService.currentPeriodStart(ANCHOR,
                ANCHOR_INSTANT.plusSeconds(720 * 3600L).minusNanos(1)));
    }

    @Test void exactEndBelongsToNewPeriod() {
        assertEquals(ANCHOR.plusHours(720), ApplicationQuotaService.currentPeriodStart(ANCHOR,
                ANCHOR_INSTANT.plusSeconds(720 * 3600L)));
    }

    @Test void inactivePeriodsAreSkippedWithoutMovingAnchor() {
        assertEquals(ANCHOR.plusHours(720 * 4), ApplicationQuotaService.currentPeriodStart(ANCHOR,
                ANCHOR_INSTANT.plusSeconds(720 * 3600L * 4 + 123)));
    }

    @Test void futureCreationTimeIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> ApplicationQuotaService.currentPeriodStart(ANCHOR, ANCHOR_INSTANT.minusNanos(1)));
    }

    @Test void absentPeriodDisplaysZeroWithoutWriting() {
        when(applications.selectOne(any(Wrapper.class))).thenReturn(application(0));
        var response = service.queryMyApplicationQuota(1L, new QueryQuotaRequest(2L));
        assertAll(() -> assertEquals(ANCHOR, response.periodStart()),
                () -> assertEquals(ANCHOR.plusHours(720), response.periodEnd()),
                () -> assertEquals(1000, response.quotaLimit()),
                () -> assertEquals(0, response.usedCount()),
                () -> assertEquals(1000, response.restCount()));
        verify(quotas).selectPeriod(2L, ANCHOR);
        verifyNoMoreInteractions(quotas);
    }

    @Test void disabledApplicationCanStillShowSnapshot() {
        when(applications.selectOne(any(Wrapper.class))).thenReturn(application(1));
        ApplicationQuota quota = new ApplicationQuota();
        quota.setQuotaLimit(50);
        quota.setUsedCount(49);
        when(quotas.selectPeriod(2L, ANCHOR)).thenReturn(quota);
        var response = service.queryMyApplicationQuota(1L, new QueryQuotaRequest(2L));
        assertEquals(50, response.quotaLimit());
        assertEquals(1, response.restCount());
    }

    @Test void missingOrForeignApplicationDoesNotReadQuota() {
        assertThrows(ApplicationNotFoundException.class,
                () -> service.queryMyApplicationQuota(1L, new QueryQuotaRequest(2L)));
        verifyNoInteractions(quotas);
    }

    @Test void invalidQueryIdIsRejectedBeforeDatabaseAccess() {
        assertThrows(InvalidApplicationIdException.class, () -> service.queryMyApplicationQuota(1L, null));
        assertThrows(InvalidApplicationIdException.class,
                () -> service.queryMyApplicationQuota(1L, new QueryQuotaRequest(0L)));
        verifyNoInteractions(applications, quotas);
    }

    @Test void invalidIdentityIsRejectedBeforeDatabaseAccess() {
        assertThrows(InvalidApiKeyCredentialException.class, () -> service.consumeOnce(null));
        assertThrows(InvalidApiKeyCredentialException.class,
                () -> service.consumeOnce(new ApiKeyIdentity(null, 2L)));
        verifyNoInteractions(applications, keys, quotas);
    }

    @Test void enclosingTransactionIsRejectedBeforeAcquiringLocks() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalTransactionStateException.class,
                    () -> service.consumeOnce(new ApiKeyIdentity(3L, 2L)));
            verifyNoInteractions(applications, keys, quotas);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void emptyBatchDoesNotQueryDatabase() {
        assertTrue(service.queryCurrentQuotas(List.of()).isEmpty());
        verifyNoInteractions(quotas, applications, keys);
    }

    @Test void batchMatchesDifferentAnchorsAndPreservesInputOrderWithoutCreatingMissingPeriod() {
        Application first = application(0);
        Application second = application(1);
        second.setId(3L);
        second.setCreatedAt(ANCHOR.minusHours(721));
        second.setQuota(500);
        ApplicationQuota stored = new ApplicationQuota();
        stored.setApplicationId(2L);
        stored.setQuotaLimit(25);
        stored.setUsedCount(7);
        when(quotas.selectList(any(Wrapper.class))).thenReturn(List.of(stored));
        var responses = service.queryCurrentQuotas(List.of(second, first));
        assertEquals(ANCHOR.minusHours(1), responses.get(0).periodStart());
        assertEquals(500, responses.get(0).quotaLimit());
        assertEquals(0, responses.get(0).usedCount());
        assertEquals(ANCHOR, responses.get(1).periodStart());
        assertEquals(25, responses.get(1).quotaLimit());
        assertEquals(7, responses.get(1).usedCount());
        verify(quotas, times(1)).selectList(any(Wrapper.class));
        verifyNoMoreInteractions(quotas);
        verifyNoInteractions(applications, keys);
    }

    @Test void batchUsesOneClockInstantForAllApplications() {
        Clock movingClock = mock(Clock.class);
        when(movingClock.instant()).thenReturn(ANCHOR_INSTANT.plusSeconds(720 * 3600L).minusNanos(1),
                ANCHOR_INSTANT.plusSeconds(720 * 3600L));
        var batchService = new ApplicationQuotaService(applications, keys, quotas, movingClock,
                mock(PlatformTransactionManager.class));
        Application first = application(0), second = application(0);
        second.setId(3L);
        when(quotas.selectList(any(Wrapper.class))).thenReturn(List.of());
        var responses = batchService.queryCurrentQuotas(List.of(first, second));
        assertEquals(ANCHOR, responses.get(0).periodStart());
        assertEquals(ANCHOR, responses.get(1).periodStart());
        verify(movingClock, times(1)).instant();
    }

    @Test void batchDatabaseFailurePropagatesInsteadOfDisplayingZero() {
        var failure = new org.springframework.dao.DataAccessResourceFailureException("simulated query failure");
        when(quotas.selectList(any(Wrapper.class))).thenThrow(failure);
        assertSame(failure, assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> service.queryCurrentQuotas(List.of(application(0)))));
    }

    private Application application(int status) {
        Application application = new Application();
        application.setId(2L);
        application.setCreatedAt(ANCHOR);
        application.setQuota(1000);
        application.setStatus(status);
        return application;
    }
}
