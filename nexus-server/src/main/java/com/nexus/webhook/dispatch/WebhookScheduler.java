package com.nexus.webhook.dispatch;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class WebhookScheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(WebhookScheduler.class);
    private final WebhookDispatcher dispatcher;
    private final WebhookLeaseRecovery recovery;
    private final WebhookWorkerImpl worker;
    private final ThreadPoolExecutor executor;
    private final boolean enabled;
    private final long dispatchMillis;
    private final long recoveryMillis;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> dispatchTask;
    private ScheduledFuture<?> recoveryTask;
    private volatile boolean running;

    public WebhookScheduler(WebhookDispatcher dispatcher, WebhookLeaseRecovery recovery, WebhookWorkerImpl worker,
            @Qualifier("webhookExecutor") ThreadPoolExecutor executor,
            @Value("${nexus.webhook.scheduling.enabled:true}") boolean enabled,
            @Value("${nexus.webhook.scheduling.dispatch-delay:1s}") String dispatchDelay,
            @Value("${nexus.webhook.scheduling.recovery-delay:5s}") String recoveryDelay) {
        this.dispatcher = dispatcher; this.recovery = recovery; this.worker = worker; this.executor = executor;
        this.enabled = enabled;
        this.dispatchMillis = positiveMillis(dispatchDelay);
        this.recoveryMillis = positiveMillis(recoveryDelay);
    }

    private static long positiveMillis(String text) {
        Duration duration = DurationStyle.detectAndParse(text);
        long millis = duration.toMillis();
        if (millis < 1) throw new IllegalArgumentException("webhook scheduling delay must be at least 1ms");
        return millis;
    }

    @Override public synchronized void start() {
        if (!enabled || running) return;
        if (executor.isShutdown()) throw new IllegalStateException("webhook scheduler cannot restart after shutdown");
        scheduler = Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("webhook-scheduler-", 0).factory());
        running = true;
        dispatchTask = scheduler.scheduleWithFixedDelay(() -> safely("dispatch", dispatcher::dispatchDueEvents), 0, dispatchMillis, TimeUnit.MILLISECONDS);
        recoveryTask = scheduler.scheduleWithFixedDelay(() -> safely("recovery", recovery::recoverExpired), 0, recoveryMillis, TimeUnit.MILLISECONDS);
    }

    private void safely(String name, Runnable action) {
        if (!running) return;
        try { action.run(); }
        catch (RuntimeException e) { log.error("Webhook scheduled task failed: {} ({})", name, e.getClass().getSimpleName()); }
    }

    @Override public synchronized void stop() {
        if (!running) return;
        running = false;
        dispatchTask.cancel(false);
        recoveryTask.cancel(false);
        dispatcher.stopAccepting();
        scheduler.shutdown();
        terminate(scheduler, "scheduler");
        executor.shutdown();
        worker.close();
        terminate(executor, "worker executor");
    }

    static void terminate(java.util.concurrent.ExecutorService service, String name) {
        boolean interrupted = false;
        try {
            try {
                if (service.awaitTermination(10, TimeUnit.SECONDS)) return;
            } catch (InterruptedException e) {
                interrupted = true;
            }
            service.shutdownNow();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!service.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { service.awaitTermination(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (!service.isTerminated()) log.error("Webhook {} did not terminate after forced shutdown", name);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    @Override public boolean isRunning() { return running; }
}
