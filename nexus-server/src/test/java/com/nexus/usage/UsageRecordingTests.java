package com.nexus.usage;

import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.openapi.controller.UuidOpenApiController;
import com.nexus.openapi.web.MachineIdentityResolver;
import com.nexus.quota.service.ApplicationQuotaService;
import com.nexus.usage.config.AsyncConfig;
import com.nexus.usage.entity.UsageEvent;
import com.nexus.usage.mapper.UsageEventMapper;
import com.nexus.usage.metrics.UsageRecordMetrics;
import com.nexus.usage.service.UsageEventWriter;
import com.nexus.usage.service.UsageRecordService;
import com.nexus.usage.web.UsageInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.method.HandlerMethod;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static com.nexus.usage.metrics.UsageRecordMetrics.FailureReason.DUPLICATE_UNCONFIRMED;
import static com.nexus.usage.metrics.UsageRecordMetrics.FailureReason.QUEUE_REJECTED;
import static com.nexus.usage.metrics.UsageRecordMetrics.FailureReason.TRANSIENT_WRITE_EXHAUSTED;
import static com.nexus.usage.metrics.UsageRecordMetrics.FailureReason.WRITE_FAILED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class UsageRecordingTests {
    @Test
    void retriesDatabaseWriteOnWorkerThreadWithSameEvent() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageEventMapper mapper = mock(UsageEventMapper.class);
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch saved = new CountDownLatch(1);
        List<UsageEvent> events = new CopyOnWriteArrayList<>();
        List<String> threadNames = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            events.add(invocation.getArgument(0));
            threadNames.add(Thread.currentThread().getName());
            if (attempts.incrementAndGet() < 3) {
                throw new TransientDataAccessResourceException("temporary database failure");
            }
            saved.countDown();
            return 1;
        }).when(mapper).insert(any(UsageEvent.class));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfig.class, UsageRecordService.class,
                    UsageEventWriter.class, UsageRecordMetrics.class);
            context.registerBean(UsageEventMapper.class, () -> mapper);
            registerUsageWriterDependencies(context);
            context.registerBean(MeterRegistry.class, () -> registry);
            context.refresh();

            String eventId = UUID.randomUUID().toString();
            context.getBean(UsageRecordService.class).record(ApiCode.UUID_GENERATE,
                    new ApiKeyIdentity(7L, 8L), 200, 2_000_000L,
                    LocalDateTime.of(2026, 10, 8, 0, 0), eventId);

            assertTrue(saved.await(5, TimeUnit.SECONDS), "异步写入应在有限时间内完成");
            assertEquals(3, attempts.get());
            assertEquals(3, events.size());
            assertTrue(events.stream().allMatch(event -> event == events.getFirst()));
            assertTrue(events.stream().allMatch(event -> eventId.equals(event.getEventId())));
            assertTrue(threadNames.stream().allMatch(name -> name.startsWith("usage-")));
            assertAllFailureCountsAreZero(registry);
        }
    }

    @Test
    void usageWriterLocksApplicationThenApiKeyBeforeInsertInOneTransaction() {
        UsageEventMapper usageEvents = mock(UsageEventMapper.class);
        ApplicationMapper applications = mock(ApplicationMapper.class);
        ApiKeyMapper apiKeys = mock(ApiKeyMapper.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
        UsageEvent event = new UsageEvent();
        event.setApplicationId(8L);
        event.setApiKeyId(7L);

        new UsageEventWriter(usageEvents, applications, apiKeys, transactions)
                .insertWithRetry(event);

        var order = inOrder(transactions, applications, apiKeys, usageEvents);
        order.verify(transactions).getTransaction(any(TransactionDefinition.class));
        order.verify(applications).selectIdForShare(8L);
        order.verify(apiKeys).selectIdForShare(7L, 8L);
        order.verify(usageEvents).insert(event);
        order.verify(transactions).commit(transaction);
    }

    @Test
    void rejectedUsageTaskDoesNotChangeApiResponse(CapturedOutput output) throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageRecordService recordService = mock(UsageRecordService.class);
        doThrow(new TaskRejectedException("queue full")).when(recordService)
                .record(any(), any(), anyInt(), anyLong(), any(), any());
        UsageInterceptor interceptor = new UsageInterceptor(
                recordService, new UsageRecordMetrics(registry));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        ApiKeyIdentity identity = new ApiKeyIdentity(7L, 8L);
        request.setAttribute(MachineIdentityResolver.ATTRIBUTE_NAME, identity);
        HandlerMethod handler = new HandlerMethod(
                new UuidOpenApiController(new MachineIdentityResolver(), org.mockito.Mockito.mock(ApplicationQuotaService.class)),
                UuidOpenApiController.class.getMethod("generateUuid", HttpServletRequest.class));

        assertTrue(interceptor.preHandle(request, response, handler));
        assertDoesNotThrow(() -> interceptor.afterCompletion(request, response, handler, null));
        assertEquals(200, response.getStatus());
        verify(recordService).record(any(), any(), anyInt(), anyLong(), any(), any());
        assertTrue(output.getOut().contains("eventId="));
        assertTrue(output.getOut().contains("用量记录队列已满"));
        assertEquals(1.0, failureCount(registry, QUEUE_REJECTED));
        assertEquals(0.0, failureCount(registry, TRANSIENT_WRITE_EXHAUSTED));
        assertEquals(0.0, failureCount(registry, DUPLICATE_UNCONFIRMED));
        assertEquals(0.0, failureCount(registry, WRITE_FAILED));
    }

    @Test
    void unhandledExceptionBeforeResponseCommitIsRecordedAs500() throws Exception {
        UsageRecordService recordService = mock(UsageRecordService.class);
        UsageInterceptor interceptor = new UsageInterceptor(
                recordService, new UsageRecordMetrics(new SimpleMeterRegistry()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        ApiKeyIdentity identity = new ApiKeyIdentity(7L, 8L);
        request.setAttribute(MachineIdentityResolver.ATTRIBUTE_NAME, identity);
        HandlerMethod handler = new HandlerMethod(
                new UuidOpenApiController(mock(MachineIdentityResolver.class),
                        mock(ApplicationQuotaService.class)),
                UuidOpenApiController.class.getMethod("generateUuid", HttpServletRequest.class));

        interceptor.preHandle(request, response, handler);
        interceptor.afterCompletion(
                request, response, handler, new IllegalStateException("test-only"));

        assertEquals(200, response.getStatus());
        verify(recordService).record(eq(ApiCode.UUID_GENERATE), eq(identity),
                eq(500), anyLong(), any(LocalDateTime.class), any(String.class));
    }

    @Test
    void duplicateEventIdAlreadyInDatabaseIsTreatedAsRecorded(CapturedOutput output) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageEventWriter writer = mock(UsageEventWriter.class);
        UsageEventMapper mapper = mock(UsageEventMapper.class);
        String eventId = UUID.randomUUID().toString();
        doThrow(new DuplicateKeyException("duplicate event"))
                .when(writer).insertWithRetry(any(UsageEvent.class));
        when(mapper.selectOne(any())).thenReturn(new UsageEvent());

        UsageRecordService service = new UsageRecordService(
                writer, mapper, new UsageRecordMetrics(registry));
        assertDoesNotThrow(() -> service.record(ApiCode.UUID_GENERATE,
                new ApiKeyIdentity(7L, 8L), 200, 2_000_000L,
                LocalDateTime.of(2026, 10, 8, 0, 0), eventId));

        verify(mapper).selectOne(any());
        assertFalse(output.getOut().contains("用量记录入库失败"));
        assertAllFailureCountsAreZero(registry);
    }

    @Test
    void unrelatedDuplicateKeyIsLoggedWhenEventIdIsAbsent(CapturedOutput output) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageEventWriter writer = mock(UsageEventWriter.class);
        UsageEventMapper mapper = mock(UsageEventMapper.class);
        String eventId = UUID.randomUUID().toString();
        doThrow(new DuplicateKeyException("other unique key"))
                .when(writer).insertWithRetry(any(UsageEvent.class));

        UsageRecordService service = new UsageRecordService(
                writer, mapper, new UsageRecordMetrics(registry));
        assertDoesNotThrow(() -> service.record(ApiCode.UUID_GENERATE,
                new ApiKeyIdentity(7L, 8L), 200, 2_000_000L,
                LocalDateTime.of(2026, 10, 8, 0, 0), eventId));

        verify(mapper).selectOne(any());
        assertTrue(output.getOut().contains("重复键且未确认事件已写入"));
        assertEquals(1.0, failureCount(registry, DUPLICATE_UNCONFIRMED));
    }

    @Test
    void failedDuplicateLookupIsLoggedWithoutEscapingAsyncEntry(CapturedOutput output) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageEventWriter writer = mock(UsageEventWriter.class);
        UsageEventMapper mapper = mock(UsageEventMapper.class);
        String eventId = UUID.randomUUID().toString();
        doThrow(new DuplicateKeyException("duplicate event"))
                .when(writer).insertWithRetry(any(UsageEvent.class));
        when(mapper.selectOne(any()))
                .thenThrow(new TransientDataAccessResourceException("lookup unavailable"));

        UsageRecordService service = new UsageRecordService(
                writer, mapper, new UsageRecordMetrics(registry));
        assertDoesNotThrow(() -> service.record(ApiCode.UUID_GENERATE,
                new ApiKeyIdentity(7L, 8L), 200, 2_000_000L,
                LocalDateTime.of(2026, 10, 8, 0, 0), eventId));

        assertEquals(1, output.getOut().split("重复键且未确认事件已写入", -1).length - 1);
        assertTrue(output.getOut().contains("lookup unavailable"));
        assertEquals(1.0, failureCount(registry, DUPLICATE_UNCONFIRMED));
    }

    @Test
    void exhaustedTransientWriteIsCountedOnceAfterAllRetries() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageEventMapper mapper = mock(UsageEventMapper.class);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new TransientDataAccessResourceException("still unavailable");
        }).when(mapper).insert(any(UsageEvent.class));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfig.class, UsageRecordService.class,
                    UsageEventWriter.class, UsageRecordMetrics.class);
            context.registerBean(UsageEventMapper.class, () -> mapper);
            registerUsageWriterDependencies(context);
            context.registerBean(MeterRegistry.class, () -> registry);
            context.refresh();

            context.getBean(UsageRecordService.class).record(ApiCode.UUID_GENERATE,
                    new ApiKeyIdentity(7L, 8L), 200, 2_000_000L,
                    LocalDateTime.of(2026, 10, 8, 0, 0), UUID.randomUUID().toString());

            awaitFailureCount(registry, TRANSIENT_WRITE_EXHAUSTED, 1.0);
            assertEquals(3, attempts.get());
            assertEquals(1.0, failureCount(registry, TRANSIENT_WRITE_EXHAUSTED));
            assertEquals(0.0, failureCount(registry, WRITE_FAILED));
        }
    }

    @Test
    void unexpectedWriteFailureIsCountedOnce() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsageEventWriter writer = mock(UsageEventWriter.class);
        doThrow(new IllegalStateException("test-only"))
                .when(writer).insertWithRetry(any(UsageEvent.class));
        UsageRecordService service = new UsageRecordService(
                writer, mock(UsageEventMapper.class), new UsageRecordMetrics(registry));

        assertDoesNotThrow(() -> service.record(ApiCode.UUID_GENERATE,
                new ApiKeyIdentity(7L, 8L), 200, 2_000_000L,
                LocalDateTime.of(2026, 10, 8, 0, 0), UUID.randomUUID().toString()));

        assertEquals(1.0, failureCount(registry, WRITE_FAILED));
        assertEquals(0.0, failureCount(registry, TRANSIENT_WRITE_EXHAUSTED));
    }

    private static void assertAllFailureCountsAreZero(SimpleMeterRegistry registry) {
        assertEquals(0.0, failureCount(registry, QUEUE_REJECTED));
        assertEquals(0.0, failureCount(registry, TRANSIENT_WRITE_EXHAUSTED));
        assertEquals(0.0, failureCount(registry, DUPLICATE_UNCONFIRMED));
        assertEquals(0.0, failureCount(registry, WRITE_FAILED));
    }

    private static void registerUsageWriterDependencies(
            AnnotationConfigApplicationContext context) {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> mock(TransactionStatus.class));
        context.registerBean(ApplicationMapper.class, () -> mock(ApplicationMapper.class));
        context.registerBean(ApiKeyMapper.class, () -> mock(ApiKeyMapper.class));
        context.registerBean(PlatformTransactionManager.class, () -> transactions);
    }

    private static double failureCount(SimpleMeterRegistry registry,
                                       UsageRecordMetrics.FailureReason reason) {
        return registry.get(UsageRecordMetrics.FAILURE_METRIC_NAME)
                .tag("reason", reason.tagValue())
                .counter()
                .count();
    }

    private static void awaitFailureCount(SimpleMeterRegistry registry,
                                          UsageRecordMetrics.FailureReason reason,
                                          double expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (failureCount(registry, reason) == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(expected, failureCount(registry, reason));
    }
}
