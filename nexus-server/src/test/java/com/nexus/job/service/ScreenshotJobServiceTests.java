package com.nexus.job.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.exception.ScreenshotJobCreationException;
import com.nexus.job.exception.ScreenshotJobFailedException;
import com.nexus.job.exception.ScreenshotJobNotReadyException;
import com.nexus.job.exception.ScreenshotJobQueueFullException;
import com.nexus.job.exception.ScreenshotJobResultExpiredException;
import com.nexus.job.exception.ScreenshotJobSubmissionUnavailableException;
import com.nexus.job.mapper.ScreenshotJobMapper;
import com.nexus.job.model.ScreenshotJobStatus;
import com.nexus.job.store.ScreenshotResultStore;
import com.nexus.quota.service.ApplicationQuotaService;
import com.nexus.screenshot.service.ScreenshotUrlPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ScreenshotJobServiceTests {
    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");
    private static final String JOB_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String URL = "https://blog.csdn.net/article";
    private final ScreenshotJobMapper jobs = mock(ScreenshotJobMapper.class);
    private final ApplicationQuotaService quotas = mock(ApplicationQuotaService.class);
    private final ScreenshotResultStore results = mock(ScreenshotResultStore.class);
    private final ApiKeyIdentity identity = new ApiKeyIdentity(7L, 3L);
    private final ScreenshotJobService service = new ScreenshotJobService(
            jobs, new ScreenshotUrlPolicy(), quotas, results, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void fullQueueRejectsBeforeQuotaConsumption() {
        when(jobs.countUnfinished()).thenReturn(10L);

        assertThrows(ScreenshotJobQueueFullException.class, () -> service.submit(identity, URL));

        verifyNoInteractions(quotas, results);
        verify(jobs, never()).insert(any(ScreenshotJob.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void admittedSubmissionInsertsOneQueuedJobInsideQuotaCallback() {
        when(jobs.countUnfinished()).thenReturn(9L);
        when(jobs.insert(any(ScreenshotJob.class))).thenReturn(1);
        when(quotas.consumeOnceAndCommit(eq(identity), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<ScreenshotJob>) invocation.getArgument(1)).get());

        ScreenshotJob submitted = service.submit(identity, URL);

        assertAll(
                () -> assertNotNull(submitted.getJobId()),
                () -> assertEquals(7L, submitted.getApiKeyId()),
                () -> assertEquals(URL, submitted.getTargetUrl()),
                () -> assertEquals(0, submitted.getStatus()),
                () -> assertEquals(0, submitted.getAttemptCount()),
                () -> assertEquals(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), submitted.getCreatedAt())
        );
        verify(jobs).insert(same(submitted));
    }

    @Test
    @SuppressWarnings("unchecked")
    void databaseFailureDuringAtomicAdmissionBecomesSubmissionUnavailable() {
        when(jobs.countUnfinished()).thenReturn(0L);
        when(quotas.consumeOnceAndCommit(eq(identity), any(Supplier.class)))
                .thenThrow(new DataAccessResourceFailureException("test-only"));

        assertThrows(ScreenshotJobSubmissionUnavailableException.class,
                () -> service.submit(identity, URL));
    }

    @Test
    @SuppressWarnings("unchecked")
    void unexpectedInsertCountBecomesCreationFailure() {
        when(jobs.countUnfinished()).thenReturn(0L);
        when(jobs.insert(any(ScreenshotJob.class))).thenReturn(0);
        when(quotas.consumeOnceAndCommit(eq(identity), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<ScreenshotJob>) invocation.getArgument(1)).get());

        assertThrows(ScreenshotJobCreationException.class,
                () -> service.submit(identity, URL));
    }

    @Test
    void queuedOrRunningJobHasNoDownloadableResult() {
        when(jobs.selectOne(any(Wrapper.class))).thenReturn(job(0), job(1));

        ScreenshotJobNotReadyException queued = assertThrows(
                ScreenshotJobNotReadyException.class, () -> service.getResult(identity, JOB_ID));
        ScreenshotJobNotReadyException running = assertThrows(
                ScreenshotJobNotReadyException.class, () -> service.getResult(identity, JOB_ID));

        assertEquals(ScreenshotJobStatus.QUEUED, queued.status());
        assertEquals(ScreenshotJobStatus.RUNNING, running.status());

        verifyNoInteractions(results);
    }

    @Test
    void statusQueryReturnsEffectiveExpiryBeforeMaintenanceUpdatesTheRow() {
        ScreenshotJob job = job(2);
        job.setResultExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(jobs.selectOne(any(Wrapper.class))).thenReturn(job);

        ScreenshotJob returned = service.getJob(identity, JOB_ID);

        assertEquals(4, returned.getStatus());
    }

    @Test
    void unexpiredSuccessfulJobReadsPng() {
        ScreenshotJob job = job(2);
        job.setResultExpiresAt(LocalDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC));
        byte[] png = new byte[]{1, 2, 3};
        when(jobs.selectOne(any(Wrapper.class))).thenReturn(job);
        when(results.read(JOB_ID)).thenReturn(png);

        assertSame(png, service.getResult(identity, JOB_ID));
    }

    @Test
    void expiryTimeIsAuthoritativeBeforeMaintenanceChangesStatus() {
        ScreenshotJob job = job(2);
        job.setResultExpiresAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(jobs.selectOne(any(Wrapper.class))).thenReturn(job);

        assertThrows(ScreenshotJobResultExpiredException.class,
                () -> service.getResult(identity, JOB_ID));

        verifyNoInteractions(results);
    }

    @Test
    void failedJobReturnsItsStableFailureCodeWithoutReadingFile() {
        ScreenshotJob job = job(3);
        job.setFailureCode("SCREENSHOT_TIMEOUT");
        when(jobs.selectOne(any(Wrapper.class))).thenReturn(job);

        ScreenshotJobFailedException exception = assertThrows(
                ScreenshotJobFailedException.class, () -> service.getResult(identity, JOB_ID));

        assertEquals("SCREENSHOT_TIMEOUT", exception.failureCode());
        verifyNoInteractions(results);
    }

    private static ScreenshotJob job(int status) {
        ScreenshotJob job = new ScreenshotJob();
        job.setJobId(JOB_ID);
        job.setApiKeyId(7L);
        job.setStatus(status);
        return job;
    }
}
