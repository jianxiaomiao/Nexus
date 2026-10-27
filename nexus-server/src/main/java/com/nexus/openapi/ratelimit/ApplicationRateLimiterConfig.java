package com.nexus.openapi.ratelimit;

import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class ApplicationRateLimiterConfig {

    @Bean
    MeterBinder applicationRateLimiterMetrics(
            ApplicationRateLimiter rateLimiter) {
        return new ApplicationRateLimiterMetrics(rateLimiter);
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService applicationRateScheduler() {
        // 专用单线程 scheduler
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform()
                        .name("application-rate-limiter-", 0)
                        .factory());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ApplicationRateLimiter applicationRateLimiter(
            ScheduledExecutorService applicationRateScheduler) {
        return new ApplicationRateLimiter(
                applicationRateScheduler,
                10,
                100,
                1000,
                Duration.ofMillis(100),
                Duration.ofSeconds(1),
                System::nanoTime
        );
    }
}
