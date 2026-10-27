package com.nexus.screenshot.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 同一 Nexus JVM 内，同一时刻只允许一个同步或异步截图进入 Chromium。 */
@Component
public class ScreenshotExecutionGate {
    private final Semaphore permit = new Semaphore(1, true);

    /** 0 时长的 timed tryAcquire 会遵守公平队列，不越过已等待的异步 Worker。 */
    public boolean tryAcquireForSynchronousRequest() {
        try {
            return permit.tryAcquire(0, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 异步任务已经持久化排队，可以等待当前截图结束，但领取前不会开始计算租约。 */
    public void acquireForAsynchronousJob() throws InterruptedException {
        permit.acquire();
    }

    public void release() {
        permit.release();
    }

    public int currentBusySlotCount() {
        return permit.availablePermits() == 0 ? 1 : 0;
    }

    boolean hasQueuedThreads() {
        return permit.hasQueuedThreads();
    }
}
