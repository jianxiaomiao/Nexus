package com.nexus.usage;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.openapi.controller.UuidOpenApiController;
import com.nexus.openapi.web.MachineIdentityResolver;
import com.nexus.usage.config.AsyncConfig;
import com.nexus.usage.entity.UsageEvent;
import com.nexus.usage.mapper.UsageEventMapper;
import com.nexus.usage.service.UsageEventWriter;
import com.nexus.usage.service.UsageRecordService;
import com.nexus.usage.web.UsageInterceptor;
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
import org.springframework.web.method.HandlerMethod;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class UsageRecordingTests {
    @Test
    void retriesDatabaseWriteOnWorkerThreadWithSameEvent() throws Exception {
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
            context.register(AsyncConfig.class, UsageRecordService.class, UsageEventWriter.class);
            context.registerBean(UsageEventMapper.class, () -> mapper);
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
        }
    }

    @Test
    void rejectedUsageTaskDoesNotChangeApiResponse() throws Exception {
        UsageRecordService recordService = mock(UsageRecordService.class);
        doThrow(new TaskRejectedException("queue full")).when(recordService)
                .record(any(), any(), anyInt(), anyLong(), any(), any());
        UsageInterceptor interceptor = new UsageInterceptor(recordService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        ApiKeyIdentity identity = new ApiKeyIdentity(7L, 8L);
        request.setAttribute(MachineIdentityResolver.ATTRIBUTE_NAME, identity);
        HandlerMethod handler = new HandlerMethod(
                new UuidOpenApiController(new MachineIdentityResolver()),
                UuidOpenApiController.class.getMethod("generateUuid", HttpServletRequest.class));

        assertTrue(interceptor.preHandle(request, response, handler));
        assertDoesNotThrow(() -> interceptor.afterCompletion(request, response, handler, null));
        assertEquals(200, response.getStatus());
        verify(recordService).record(any(), any(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void duplicateEventIdAlreadyInDatabaseIsTreatedAsRecorded(CapturedOutput output) {
        UsageEventWriter writer = mock(UsageEventWriter.class);
        UsageEventMapper mapper = mock(UsageEventMapper.class);
        String eventId = UUID.randomUUID().toString();
        doThrow(new DuplicateKeyException("duplicate event"))
                .when(writer).insertWithRetry(any(UsageEvent.class));
        when(mapper.selectOne(any())).thenReturn(new UsageEvent());

        UsageRecordService service = new UsageRecordService(writer, mapper);
        assertDoesNotThrow(() -> service.record(ApiCode.UUID_GENERATE,
                new ApiKeyIdentity(7L, 8L), 200, 2_000_000L,
                LocalDateTime.of(2026, 10, 8, 0, 0), eventId));

        verify(mapper).selectOne(any());
        assertFalse(output.getOut().contains("用量记录入库失败"));
    }

    @Test
    void unrelatedDuplicateKeyIsLoggedWhenEventIdIsAbsent(CapturedOutput output) {
        UsageEventWriter writer = mock(UsageEventWriter.class);
        UsageEventMapper mapper = mock(UsageEventMapper.class);
        String eventId = UUID.randomUUID().toString();
        doThrow(new DuplicateKeyException("other unique key"))
                .when(writer).insertWithRetry(any(UsageEvent.class));

        UsageRecordService service = new UsageRecordService(writer, mapper);
        assertDoesNotThrow(() -> service.record(ApiCode.UUID_GENERATE,
                new ApiKeyIdentity(7L, 8L), 200, 2_000_000L,
                LocalDateTime.of(2026, 10, 8, 0, 0), eventId));

        verify(mapper).selectOne(any());
        assertTrue(output.getOut().contains("重复键且未确认事件已写入"));
    }

    @Test
    void failedDuplicateLookupIsLoggedWithoutEscapingAsyncEntry(CapturedOutput output) {
        UsageEventWriter writer = mock(UsageEventWriter.class);
        UsageEventMapper mapper = mock(UsageEventMapper.class);
        String eventId = UUID.randomUUID().toString();
        doThrow(new DuplicateKeyException("duplicate event"))
                .when(writer).insertWithRetry(any(UsageEvent.class));
        when(mapper.selectOne(any()))
                .thenThrow(new TransientDataAccessResourceException("lookup unavailable"));

        UsageRecordService service = new UsageRecordService(writer, mapper);
        assertDoesNotThrow(() -> service.record(ApiCode.UUID_GENERATE,
                new ApiKeyIdentity(7L, 8L), 200, 2_000_000L,
                LocalDateTime.of(2026, 10, 8, 0, 0), eventId));

        assertTrue(output.getOut().contains("用量事件去重查询失败"));
        assertTrue(output.getOut().contains("重复键且未确认事件已写入"));
    }
}
