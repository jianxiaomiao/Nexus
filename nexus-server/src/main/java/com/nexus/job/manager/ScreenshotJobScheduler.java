package com.nexus.job.manager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ScreenshotJobScheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ScreenshotJobScheduler.class);

    private final ScreenshotJobManager manager;
    private final ScheduledExecutorService worker;
    private final ScheduledExecutorService maintenance;
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();

    public ScreenshotJobScheduler(
            ScreenshotJobManager manager,
            @Qualifier("screenshotJobWorkerScheduler") ScheduledExecutorService worker,
            @Qualifier("screenshotJobMaintenanceScheduler") ScheduledExecutorService maintenance) {
        this.manager = manager;
        this.worker = worker;
        this.maintenance = maintenance;
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        tasks.add(worker.scheduleWithFixedDelay(
                () -> runSafely("worker", manager::pollAndRunOne), 0, 1, TimeUnit.SECONDS));
        tasks.add(maintenance.scheduleWithFixedDelay(
                () -> runSafely("lease-recovery", manager::recoverExpiredLeases), 0, 5, TimeUnit.SECONDS));
        tasks.add(maintenance.scheduleWithFixedDelay(
                () -> runSafely("result-expiry", manager::expireResults), 0, 1, TimeUnit.MINUTES));
        tasks.add(maintenance.scheduleWithFixedDelay(
                () -> runSafely("metadata-cleanup", manager::cleanupMetadata), 0, 10, TimeUnit.MINUTES));
    }

    @Override
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (ScheduledFuture<?> task : tasks) {
            task.cancel(false);
        }
        tasks.clear();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void runSafely(String taskName, Runnable task) {
        if (!running.get()) {
            return;
        }
        try {
            task.run();
        } catch (RuntimeException exception) {
            // 周期任务抛出异常会被 executor 永久取消，因此在边界记录后允许下一轮继续。
            log.error("异步截图周期任务失败 task={}, exception={}",
                    taskName, exception.getClass().getSimpleName(), exception);
        }
    }
}
