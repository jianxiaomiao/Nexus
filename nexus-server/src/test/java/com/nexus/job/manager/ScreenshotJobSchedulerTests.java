package com.nexus.job.manager;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScreenshotJobSchedulerTests {
    @Test
    void startsFourTasksOnTheAgreedExecutorsAndCancelsThemOnStop() {
        ScreenshotJobManager manager = mock(ScreenshotJobManager.class);
        ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
        ScheduledExecutorService maintenance = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> workerFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> recoveryFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> expiryFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> cleanupFuture = mock(ScheduledFuture.class);
        doReturn(workerFuture).when(worker)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        doReturn(recoveryFuture, expiryFuture, cleanupFuture).when(maintenance)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        ScreenshotJobScheduler scheduler = new ScreenshotJobScheduler(manager, worker, maintenance);

        scheduler.start();
        scheduler.start();

        assertTrue(scheduler.isRunning());
        verify(worker).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(1L), eq(TimeUnit.SECONDS));
        verify(maintenance).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
        verify(maintenance).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(1L), eq(TimeUnit.MINUTES));
        verify(maintenance).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(10L), eq(TimeUnit.MINUTES));

        scheduler.stop();
        scheduler.stop();

        assertFalse(scheduler.isRunning());
        verify(workerFuture).cancel(false);
        verify(recoveryFuture).cancel(false);
        verify(expiryFuture).cancel(false);
        verify(cleanupFuture).cancel(false);
    }

    @Test
    void taskFailureIsContainedSoThePeriodicRunnableCanRunAgain() {
        ScreenshotJobManager manager = mock(ScreenshotJobManager.class);
        ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
        ScheduledExecutorService maintenance = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> workerCommand = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(worker)
                .scheduleWithFixedDelay(workerCommand.capture(), anyLong(), anyLong(), any(TimeUnit.class));
        doReturn(future).when(maintenance)
                .scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        when(manager.pollAndRunOne()).thenThrow(new IllegalStateException("test-only"));
        ScreenshotJobScheduler scheduler = new ScreenshotJobScheduler(manager, worker, maintenance);
        scheduler.start();

        assertDoesNotThrow(() -> workerCommand.getValue().run());
        assertDoesNotThrow(() -> workerCommand.getValue().run());
        verify(manager, times(2)).pollAndRunOne();

        scheduler.stop();
    }
}
