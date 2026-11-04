package com.nexus.webhook.dispatch;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebhookSchedulerTests {
    private final WebhookDispatcher dispatcher = mock(WebhookDispatcher.class);
    private final WebhookLeaseRecovery recovery = mock(WebhookLeaseRecovery.class);
    private final WebhookWorkerImpl worker = mock(WebhookWorkerImpl.class);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1));

    @Test void disabledDoesNotSchedule() {
        var scheduler = new WebhookScheduler(dispatcher, recovery, worker, executor, false, "10ms", "10ms");
        scheduler.start();
        assertFalse(scheduler.isRunning());
        verifyNoInteractions(dispatcher, recovery, worker);
        executor.shutdownNow();
    }

    @Test void periodicFailureDoesNotStopNextRunAndStopPreventsMoreCalls() throws Exception {
        CountDownLatch calls = new CountDownLatch(2);
        doAnswer(invocation -> { calls.countDown(); if (calls.getCount() == 1) throw new IllegalStateException(); return null; })
                .when(dispatcher).dispatchDueEvents();
        var scheduler = new WebhookScheduler(dispatcher, recovery, worker, executor, true, "10ms", "10ms");
        try {
            scheduler.start();
            assertTrue(calls.await(5, TimeUnit.SECONDS));
            scheduler.stop();
            assertFalse(scheduler.isRunning());
            int count = mockingDetails(dispatcher).getInvocations().size();
            Thread.sleep(50);
            assertEquals(count, mockingDetails(dispatcher).getInvocations().size());
            verify(worker).close();
            assertTrue(executor.isTerminated());
            var field = WebhookScheduler.class.getDeclaredField("scheduler"); field.setAccessible(true);
            assertTrue(((java.util.concurrent.ScheduledExecutorService) field.get(scheduler)).isTerminated());
        } finally { scheduler.stop(); executor.shutdownNow(); }
    }

    @Test void springContextStartsAndStopsLifecycleAutomatically() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1);
        doAnswer(invocation -> { dispatched.countDown(); return null; }).when(dispatcher).dispatchDueEvents();
        var scheduler = new WebhookScheduler(dispatcher, recovery, worker, executor, true, "20ms", "20ms");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(WebhookScheduler.class, () -> scheduler);
            context.refresh();
            assertTrue(scheduler.isRunning());
            assertTrue(dispatched.await(5, TimeUnit.SECONDS));
        }
        assertFalse(scheduler.isRunning());
        verify(worker).close();
        assertTrue(executor.isTerminated());
    }

    @Test void forcedShutdownWaitsAgainForTermination() throws Exception {
        var service = mock(java.util.concurrent.ExecutorService.class);
        when(service.awaitTermination(10, TimeUnit.SECONDS)).thenReturn(false);
        when(service.isTerminated()).thenReturn(false, true, true);
        WebhookScheduler.terminate(service, "test");
        var order = inOrder(service);
        order.verify(service).awaitTermination(10, TimeUnit.SECONDS);
        order.verify(service).shutdownNow();
        order.verify(service).awaitTermination(anyLong(), eq(TimeUnit.NANOSECONDS));
        verify(service, atLeastOnce()).isTerminated();
    }

    @Test void interruptedShutdownStillWaitsAndRestoresInterrupt() throws Exception {
        var service = mock(java.util.concurrent.ExecutorService.class);
        when(service.awaitTermination(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        when(service.isTerminated()).thenReturn(false, true, true);
        try {
            WebhookScheduler.terminate(service, "test");
            verify(service).shutdownNow();
            verify(service).awaitTermination(anyLong(), eq(TimeUnit.NANOSECONDS));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void blockedDispatchDoesNotBlockRecoveryOrOverlapItself() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch recovered = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return null; })
                .when(dispatcher).dispatchDueEvents();
        doAnswer(invocation -> { recovered.countDown(); return null; }).when(recovery).recoverExpired();
        var scheduler = new WebhookScheduler(dispatcher, recovery, worker, executor, true, "10ms", "10ms");
        try {
            scheduler.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(recovered.await(5, TimeUnit.SECONDS));
            verify(dispatcher, times(1)).dispatchDueEvents();
        } finally { release.countDown(); scheduler.stop(); }
    }
}
