package com.nexus.webhook.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetAddress;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class WebhookClassificationConfigTests {
    private AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(Clock.class, Clock::systemUTC);
        context.registerBean(WebhookOutboxMapper.class, () -> mock(WebhookOutboxMapper.class));
        context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
        context.register(WebhookClassificationConfig.class);
        return context;
    }

    @Test void defaultsAndOverridesBindAndExposeBeans() {
        try (var context = context(Map.of())) {
            context.refresh();
            var defaults = context.getBean(WebhookRetryPolicy.class);
            assertEquals(5, defaults.maxRetries());
            assertEquals(Duration.ofSeconds(1), defaults.baseBackoff());
            assertEquals(Duration.ofMinutes(5), defaults.maxBackoff());
            assertNotNull(context.getBean(WebhookResponseHandler.class));
            assertNotNull(context.getBean(WebhookExceptionClassifier.class));
        }
        try (var context = context(Map.of("nexus.webhook.retry.max-retries", "2", "nexus.webhook.retry.base-backoff", "2s", "nexus.webhook.retry.max-backoff", "7s"))) {
            context.refresh();
            assertEquals(new WebhookRetryPolicy(2, Duration.ofSeconds(2), Duration.ofSeconds(7)), context.getBean(WebhookRetryPolicy.class));
            var preparer = context.getBean(WebhookAttemptPreparer.class);
            assertSame(preparer, context.getBean(WebhookAttemptPreparer.class));
            var mapper = context.getBean(WebhookOutboxMapper.class);
            var manager = context.getBean(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            when(mapper.lockForCompletion(1)).thenReturn(1L);
            when(mapper.prepareAttempt(anyLong(), anyString(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(1);
            assertTrue(preparer.prepare(1, "t", new WebhookAttemptPreparer.Snapshot(2, false), WebhookAttemptPreparer.AttemptType.ORDINARY_RETRY).isPresent());
            assertTrue(preparer.prepare(1, "t", new WebhookAttemptPreparer.Snapshot(3, false), WebhookAttemptPreparer.AttemptType.ORDINARY_RETRY).isEmpty());
            verify(manager).getTransaction(any());
            verify(manager).commit(any());
        }
        try (var context = context(Map.of("nexus.webhook.retry.max-retries", "-1"))) {
            assertThrows(Exception.class, context::refresh);
        }
    }

    @Test void productionDnsRejectionHasDistinctType() throws Exception {
        assertThrows(WebhookDisallowedAddressException.class,
                () -> WebhookUrlPolicy.checked("example.com", h -> new InetAddress[]{InetAddress.getLoopbackAddress()}));
    }
}
