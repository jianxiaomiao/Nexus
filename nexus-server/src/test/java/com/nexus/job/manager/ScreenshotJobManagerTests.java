package com.nexus.job.manager;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.mapper.ScreenshotJobMapper;
import com.nexus.job.store.ScreenshotResultStore;
import com.nexus.job.store.StoredScreenshot;
import com.nexus.screenshot.exception.ScreenshotException;
import com.nexus.screenshot.metrics.ScreenshotMetrics;
import com.nexus.screenshot.service.ScreenshotExecutionGate;
import com.nexus.screenshot.service.ScreenshotRenderer;
import com.nexus.screenshot.service.ScreenshotResult;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ScreenshotJobManagerTests {
    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");
    private static final LocalDateTime UTC_NOW = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String JOB_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String URL = "https://blog.csdn.net/article";

    private final ScreenshotJobMapper jobs = mock(ScreenshotJobMapper.class);
    private final ScreenshotRenderer renderer = mock(ScreenshotRenderer.class);
    private final ScreenshotResultStore results = mock(ScreenshotResultStore.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final MockClock meterClock = new MockClock();
    private final SimpleMeterRegistry meterRegistry =
            new SimpleMeterRegistry(SimpleConfig.DEFAULT, meterClock);
    private final ScreenshotJobManager manager = new ScreenshotJobManager(
            jobs, renderer, results, new ScreenshotExecutionGate(),
            new ScreenshotMetrics(meterRegistry),
            Clock.fixed(NOW, ZoneOffset.UTC), transactions);

    @BeforeEach
    void transactionBoundary() {
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
    }

    @Test
    void noQueuedJobDoesNotTakeTheExecutionGateOrOpenAClaimTransaction() {
        when(jobs.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertFalse(manager.pollAndRunOne());

        verify(transactions, never()).getTransaction(any());
        verifyNoInteractions(renderer, results);
        assertEquals(0, queueDelayTimer().count());
        assertEquals(0, executionTimer("succeeded").count());
        assertEquals(0, executionTimer("failed").count());
    }

    @Test
    void claimedJobRendersStoresAndCommitsSuccessWithTheSameRunToken() {
        ScreenshotJob job = queuedJob();
        ScreenshotResult rendered = new ScreenshotResult(new byte[]{1, 2, 3}, 7, true);
        when(jobs.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(jobs.selectNextQueuedForUpdate()).thenReturn(job);
        when(jobs.markRunningIfQueued(eq(11L), any(), eq(UTC_NOW.plusSeconds(30)), eq(UTC_NOW)))
                .thenReturn(1);
        when(renderer.render(URI.create(URL))).thenAnswer(invocation -> {
            meterClock.add(java.time.Duration.ofMillis(125));
            return rendered;
        });
        when(results.saveAtomically(JOB_ID, rendered.png()))
                .thenReturn(new StoredScreenshot(3L, "abc123"));
        when(jobs.completeSucceededIfRunTokenMatches(
                eq(11L), any(), eq(3L), eq("abc123"), eq(7), eq(true),
                eq(UTC_NOW.plusHours(1)), eq(UTC_NOW))).thenReturn(1);

        assertTrue(manager.pollAndRunOne());

        ArgumentCaptor<String> claimedToken = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> completedToken = ArgumentCaptor.forClass(String.class);
        verify(jobs).markRunningIfQueued(eq(11L), claimedToken.capture(),
                eq(UTC_NOW.plusSeconds(30)), eq(UTC_NOW));
        verify(jobs).completeSucceededIfRunTokenMatches(
                eq(11L), completedToken.capture(), eq(3L), eq("abc123"), eq(7), eq(true),
                eq(UTC_NOW.plusHours(1)), eq(UTC_NOW));
        assertEquals(claimedToken.getValue(), completedToken.getValue());
        assertEquals(1, job.getAttemptCount());
        assertEquals(1, job.getStatus());
        verify(transactions).commit(transactionStatus);

        var order = inOrder(jobs, renderer, results);
        order.verify(jobs).markRunningIfQueued(eq(11L), any(), any(), any());
        order.verify(renderer).render(URI.create(URL));
        order.verify(results).saveAtomically(JOB_ID, rendered.png());
        order.verify(jobs).completeSucceededIfRunTokenMatches(
                eq(11L), any(), eq(3L), eq("abc123"), eq(7), eq(true), any(), any());

        assertEquals(1, queueDelayTimer().count());
        assertEquals(250,
                queueDelayTimer().totalTime(TimeUnit.MILLISECONDS), 0.0001);
        assertEquals(1, executionTimer("succeeded").count());
        assertEquals(125,
                executionTimer("succeeded").totalTime(TimeUnit.MILLISECONDS), 0.0001);
        assertEquals(0, executionTimer("failed").count());
    }

    @Test
    void knownRendererFailureIsCommittedWithStableFailureCode() {
        ScreenshotJob job = queuedJob();
        when(jobs.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(jobs.selectNextQueuedForUpdate()).thenReturn(job);
        when(jobs.markRunningIfQueued(eq(11L), any(), any(), any())).thenReturn(1);
        when(renderer.render(URI.create(URL)))
                .thenThrow(new ScreenshotException(ScreenshotException.Kind.TIMEOUT));
        when(jobs.markFailedIfRunTokenMatches(eq(11L), any(),
                eq("SCREENSHOT_TIMEOUT"), eq(UTC_NOW))).thenReturn(1);

        assertTrue(manager.pollAndRunOne());

        verify(jobs).markFailedIfRunTokenMatches(
                eq(11L), eq(job.getRunToken()), eq("SCREENSHOT_TIMEOUT"), eq(UTC_NOW));
        verifyNoInteractions(results);
        assertEquals(1, queueDelayTimer().count());
        assertEquals(1, executionTimer("failed").count());
        assertEquals(0, executionTimer("succeeded").count());
    }

    @Test
    void retryClaimDoesNotRecordQueueDelayAgain() {
        ScreenshotJob job = queuedJob();
        job.setAttemptCount(1);
        job.setStartedAt(UTC_NOW.minusMinutes(1));
        when(jobs.selectNextQueuedForUpdate()).thenReturn(job);
        when(jobs.markRunningIfQueued(eq(11L), any(), any(), any())).thenReturn(1);

        ScreenshotJob claimed = manager.claimNextJob();

        assertSame(job, claimed);
        assertEquals(2, claimed.getAttemptCount());
        assertEquals(0, queueDelayTimer().count());
    }

    @Test
    void gateAcquisitionWithoutAClaimRecordsOneFailedExecution() {
        when(jobs.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(jobs.selectNextQueuedForUpdate()).thenReturn(null);

        assertFalse(manager.pollAndRunOne());

        assertEquals(1, executionTimer("failed").count());
        assertEquals(0, executionTimer("succeeded").count());
        assertEquals(0, queueDelayTimer().count());
    }

    @Test
    void leaseRecoveryFailsExhaustedJobsBeforeRequeueingRetryableJobs() {
        manager.recoverExpiredLeases();

        var order = inOrder(jobs);
        order.verify(jobs).failExhaustedExpiredLeases(UTC_NOW, 2);
        order.verify(jobs).requeueExpiredLeases(UTC_NOW, 2);
    }

    @Test
    void resultExpiryChangesDatabaseStateBeforeDeletingTheFile() {
        ScreenshotJob job = terminalJob(21L);
        when(jobs.selectResultExpiryCandidates(UTC_NOW, 100)).thenReturn(List.of(job));
        when(jobs.markResultExpiredIfSucceeded(21L, UTC_NOW)).thenReturn(1);

        manager.expireResults();

        var order = inOrder(jobs, results);
        order.verify(jobs).markResultExpiredIfSucceeded(21L, UTC_NOW);
        order.verify(results).deleteIfExists(JOB_ID);
    }

    @Test
    void metadataCleanupDeletesTheFileBeforeDeletingItsRow() {
        ScreenshotJob job = terminalJob(31L);
        LocalDateTime cutoff = UTC_NOW.minusHours(24);
        when(jobs.selectMetadataCleanupCandidates(cutoff, 100)).thenReturn(List.of(job));

        manager.cleanupMetadata();

        var order = inOrder(results, jobs);
        order.verify(results).deleteIfExists(JOB_ID);
        order.verify(jobs).deleteTerminalIfEligible(31L, cutoff);
    }

    private static ScreenshotJob queuedJob() {
        ScreenshotJob job = new ScreenshotJob();
        job.setId(11L);
        job.setJobId(JOB_ID);
        job.setTargetUrl(URL);
        job.setStatus(0);
        job.setAttemptCount(0);
        job.setCreatedAt(UTC_NOW.minusNanos(250_000_000));
        return job;
    }

    private static ScreenshotJob terminalJob(long id) {
        ScreenshotJob job = new ScreenshotJob();
        job.setId(id);
        job.setJobId(JOB_ID);
        return job;
    }

    private Timer queueDelayTimer() {
        Timer timer = meterRegistry.find(ScreenshotMetrics.QUEUE_DELAY_METRIC_NAME).timer();
        assertNotNull(timer);
        return timer;
    }

    private Timer executionTimer(String outcome) {
        Timer timer = meterRegistry.find(ScreenshotMetrics.EXECUTION_METRIC_NAME)
                .tag("mode", "async")
                .tag("outcome", outcome)
                .timer();
        assertNotNull(timer);
        return timer;
    }
}
