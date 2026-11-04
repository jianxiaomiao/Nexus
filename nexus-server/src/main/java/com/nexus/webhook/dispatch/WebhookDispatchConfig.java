package com.nexus.webhook.dispatch;

import com.nexus.webhook.mapper.WebhookOutboxMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration(proxyBeanMethods = false)
public class WebhookDispatchConfig {
    @Bean(destroyMethod = "close")
    LeaseScheduler webhookLeaseScheduler() { return new LeaseScheduler(); }

    @Bean(destroyMethod = "close")
    WebhookLeaseManager webhookLeaseManager(WebhookOutboxMapper mapper, LeaseScheduler scheduler, Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${nexus.webhook.dispatch.lease-duration:60s}") String ttl,
            @Value("${nexus.webhook.dispatch.renewal-interval:20s}") String interval) {
        return new WebhookLeaseManager(mapper, scheduler, clock,
                DurationStyle.detectAndParse(ttl), DurationStyle.detectAndParse(interval), new TransactionTemplate(transactionManager));
    }
    @Bean(name = "webhookExecutor", destroyMethod = "shutdown")
    ThreadPoolExecutor webhookExecutor() {
        AtomicInteger threadNumber = new AtomicInteger();
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8),
                task -> new Thread(task, "webhook-dispatch-" + threadNumber.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = "webhookPermits")
    Semaphore webhookPermits() {
        return new Semaphore(4);
    }

}
