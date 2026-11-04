package com.nexus.webhook.dispatch;

import com.nexus.webhook.mapper.WebhookOutboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WebhookDispatchConfigTests {
    @Test
    void poolAndPermitsAreSingletonsAndPoolIsBoundedAndShutDown() throws Exception {
        ThreadPoolExecutor pool;
        CountDownLatch release = new CountDownLatch(1);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(WebhookOutboxMapper.class, () -> mock(WebhookOutboxMapper.class));
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(WebhookDispatchConfig.class);
            context.refresh();
            pool = context.getBean("webhookExecutor", ThreadPoolExecutor.class);
            Semaphore permits = context.getBean("webhookPermits", Semaphore.class);
            assertSame(pool, context.getBean("webhookExecutor"));
            assertSame(permits, context.getBean("webhookPermits"));
            assertEquals(4, permits.availablePermits());
            assertEquals(4, pool.getCorePoolSize());
            assertEquals(4, pool.getMaximumPoolSize());
            assertEquals(0, pool.getKeepAliveTime(TimeUnit.NANOSECONDS));
            assertInstanceOf(ArrayBlockingQueue.class, pool.getQueue());
            assertEquals(8, pool.getQueue().remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler());
            assertTrue(context.getBeansOfType(WebhookDispatcher.class).isEmpty());

            CountDownLatch started = new CountDownLatch(4);
            AtomicReference<String> threadName = new AtomicReference<>();
            Runnable blocked = () -> {
                threadName.set(Thread.currentThread().getName());
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            try {
                for (int i = 0; i < 4; i++) pool.execute(blocked);
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertTrue(threadName.get().startsWith("webhook-dispatch-"));
                for (int i = 0; i < 8; i++) pool.execute(blocked);
                assertThrows(RejectedExecutionException.class, () -> pool.execute(blocked));
            } finally {
                release.countDown();
            }
        }
        assertTrue(pool.isShutdown());
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void dispatcherUsesSharedPoolAndPermitsWhenWorkerIsProvided() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("nexus.webhook.dispatch.lease-duration", "2m")));
            context.registerBean(WebhookAttemptPreparer.class, () -> mock(WebhookAttemptPreparer.class));
            context.registerBean(com.nexus.webhook.mapper.ApplicationWebhookMapper.class, () -> mock(com.nexus.webhook.mapper.ApplicationWebhookMapper.class));
            context.registerBean(com.nexus.webhook.mapper.WebhookSecretMapper.class, () -> mock(com.nexus.webhook.mapper.WebhookSecretMapper.class));
            context.registerBean(com.nexus.webhook.crypto.WebhookSecretCrypto.class, () -> mock(com.nexus.webhook.crypto.WebhookSecretCrypto.class));
            context.registerBean(com.nexus.webhook.crypto.WebhookSignatureService.class, () -> mock(com.nexus.webhook.crypto.WebhookSignatureService.class));
            context.registerBean(tools.jackson.databind.ObjectMapper.class, () -> tools.jackson.databind.json.JsonMapper.builder().build());
            context.registerBean(WebhookResponseHandler.class, () -> mock(WebhookResponseHandler.class));
            context.registerBean(WebhookExceptionClassifier.class, () -> mock(WebhookExceptionClassifier.class));
            context.register(WebhookWorkerConfig.class);
            context.registerBean(WebhookOutboxMapper.class, () -> mock(WebhookOutboxMapper.class));
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.registerBean(Clock.class, Clock::systemUTC);
            context.register(WebhookDispatchConfig.class);
            context.refresh();
            WebhookDispatcher dispatcher = context.getBean(WebhookDispatcher.class);
            assertSame(dispatcher, context.getBean(WebhookDispatcher.class));
            assertSame(context.getBean("webhookExecutor"), ReflectionTestUtils.getField(dispatcher, "executor"));
            assertSame(context.getBean("webhookPermits"), ReflectionTestUtils.getField(dispatcher, "semaphore"));
            assertEquals(120_000_000L, ReflectionTestUtils.getField(dispatcher, "ttlMicros"));
        }
    }

    @Test
    void workerRequiresValidPositiveLeaseDuration() {
        for (String value : new String[]{"invalid", "0s", "-1s"}) {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                if (!value.isEmpty()) {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                            Map.of("nexus.webhook.dispatch.lease-duration", value)));
                }
                context.registerBean(WebhookWorker.class, () -> event -> {});
                context.registerBean(WebhookOutboxMapper.class, () -> mock(WebhookOutboxMapper.class));
                context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
                context.registerBean(Clock.class, Clock::systemUTC);
                context.register(WebhookDispatchConfig.class);
                assertThrows(org.springframework.beans.factory.BeanCreationException.class, context::refresh, value);
            }
        }
    }

}
