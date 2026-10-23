package com.nexus.job.manager;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration(proxyBeanMethods = false)
public class ScreenshotJobSchedulerConfig {
    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService screenshotJobWorkerScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("screenshot-job-worker-", 0).factory());
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService screenshotJobMaintenanceScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("screenshot-job-maintenance-", 0).factory());
    }
}
