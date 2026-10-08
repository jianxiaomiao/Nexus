package com.nexus.usage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.usage.entity.UsageEvent;
import com.nexus.usage.mapper.UsageEventMapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

public final class UsageTestCleanup {
    private UsageTestCleanup() {
    }

    public static void deleteEventsAfterPendingWrites(Executor executor, UsageEventMapper mapper, long apiKeyId)
            throws InterruptedException {
        if (!(executor instanceof ThreadPoolTaskExecutor taskExecutor)) {
            throw new IllegalArgumentException("Expected the usageExecutor ThreadPoolTaskExecutor");
        }
        ThreadPoolExecutor pool = taskExecutor.getThreadPoolExecutor();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (pool.getActiveCount() != 0 || !pool.getQueue().isEmpty()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Usage tasks did not finish before test cleanup");
            }
            Thread.sleep(10);
        }
        mapper.delete(Wrappers.<UsageEvent>lambdaQuery()
                .eq(UsageEvent::getApiKeyId, apiKeyId));
    }
}
