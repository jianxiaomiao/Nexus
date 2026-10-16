package com.nexus.openapi.web;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.common.web.ApiResponse;
import com.nexus.openapi.ratelimit.ApplicationRateLimiter;
import com.nexus.openapi.ratelimit.QueueDecision;
import com.nexus.openapi.ratelimit.RateLimitAdmission;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApplicationRateFilterTests {

    @Test
    void queuedRequestIsDispatchedAndContinuesWithoutAcquiringAgainWhenAllowed()
            throws Exception {
        CompletableFuture<QueueDecision> completion = new CompletableFuture<>();
        RateLimitAdmission.WaitHandle handle = mock(RateLimitAdmission.WaitHandle.class);
        when(handle.completion()).thenReturn(completion);
        Fixture fixture = fixture(new RateLimitAdmission.Queued(handle));

        fixture.filter().doFilter(
                fixture.request(), fixture.response(), fixture.chain());

        assertTrue(fixture.request().isAsyncStarted());
        verifyNoInteractions(fixture.chain());

        MockAsyncContext asyncContext =
                (MockAsyncContext) fixture.request().getAsyncContext();
        AtomicBoolean dispatched = new AtomicBoolean();
        asyncContext.addDispatchHandler(() -> dispatched.set(true));
        completion.complete(QueueDecision.ALLOWED);
        assertTrue(dispatched.get());

        fixture.request().setDispatcherType(DispatcherType.ASYNC);
        fixture.filter().doFilter(
                fixture.request(), fixture.response(), fixture.chain());

        verify(fixture.chain()).doFilter(fixture.request(), fixture.response());
        verify(fixture.limiter(), times(1)).acquire(20L);
    }

    @Test
    void queuedTimeoutIsDispatchedAs429WithRetryAfterWithoutContinuing()
            throws Exception {
        CompletableFuture<QueueDecision> completion = new CompletableFuture<>();
        RateLimitAdmission.WaitHandle handle = mock(RateLimitAdmission.WaitHandle.class);
        when(handle.completion()).thenReturn(completion);
        Fixture fixture = fixture(new RateLimitAdmission.Queued(handle));

        fixture.filter().doFilter(
                fixture.request(), fixture.response(), fixture.chain());
        completion.complete(QueueDecision.TIMED_OUT);

        fixture.request().setDispatcherType(DispatcherType.ASYNC);
        fixture.filter().doFilter(
                fixture.request(), fixture.response(), fixture.chain());

        assertEquals(429, fixture.response().getStatus());
        assertEquals("1", fixture.response().getHeader("Retry-After"));
        assertEquals("APPLICATION_RATE_WAIT_TIMEOUT", writtenCode(fixture.objectMapper()));
        verifyNoInteractions(fixture.chain());
        verify(fixture.limiter(), times(1)).acquire(20L);
    }

    @Test
    void servletTimeoutCancelsWaitingRequestAndDispatchesCancelledDecision()
            throws Exception {
        TestWaitHandle handle = new TestWaitHandle();
        Fixture fixture = fixture(new RateLimitAdmission.Queued(handle));

        fixture.filter().doFilter(
                fixture.request(), fixture.response(), fixture.chain());
        MockAsyncContext asyncContext = asyncContext(fixture);
        AtomicInteger dispatches = observeDispatches(asyncContext);

        asyncListener(asyncContext).onTimeout(mock(AsyncEvent.class));

        assertEquals(QueueDecision.CANCELLED, handle.decision());
        assertEquals(1, handle.terminalTransitions());
        assertEquals(1, dispatches.get());

        fixture.request().setDispatcherType(DispatcherType.ASYNC);
        fixture.filter().doFilter(
                fixture.request(), fixture.response(), fixture.chain());

        assertEquals(503, fixture.response().getStatus());
        assertEquals("RATE_LIMIT_WAIT_CANCELLED", writtenCode(fixture.objectMapper()));
        verifyNoInteractions(fixture.chain());
    }

    @Test
    void allowanceBeforeServletCompletionCannotBeReplacedByCancellation()
            throws Exception {
        TestWaitHandle handle = new TestWaitHandle();
        Fixture fixture = fixture(new RateLimitAdmission.Queued(handle));

        fixture.filter().doFilter(
                fixture.request(), fixture.response(), fixture.chain());
        MockAsyncContext asyncContext = asyncContext(fixture);
        AtomicInteger dispatches = observeDispatches(asyncContext);

        assertTrue(handle.decide(QueueDecision.ALLOWED));
        asyncListener(asyncContext).onComplete(mock(AsyncEvent.class));

        assertEquals(QueueDecision.ALLOWED, handle.decision());
        assertEquals(1, handle.terminalTransitions());
        assertEquals(1, dispatches.get());

        fixture.request().setDispatcherType(DispatcherType.ASYNC);
        fixture.filter().doFilter(
                fixture.request(), fixture.response(), fixture.chain());
        verify(fixture.chain()).doFilter(fixture.request(), fixture.response());
    }

    @RepeatedTest(20)
    void limiterShutdownAndServletErrorRaceDispatchExactlyOneTerminalDecision()
            throws Exception {
        TestWaitHandle handle = new TestWaitHandle();
        Fixture fixture = fixture(new RateLimitAdmission.Queued(handle));
        fixture.filter().doFilter(
                fixture.request(), fixture.response(), fixture.chain());
        MockAsyncContext asyncContext = asyncContext(fixture);
        AtomicInteger dispatches = observeDispatches(asyncContext);
        AsyncListener listener = asyncListener(asyncContext);
        ExecutorService racers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<?> shutdown = racers.submit(() -> {
                start.await();
                handle.decide(QueueDecision.LIMITER_UNAVAILABLE);
                return null;
            });
            Future<?> disconnect = racers.submit(() -> {
                start.await();
                listener.onError(mock(AsyncEvent.class));
                return null;
            });

            start.countDown();
            shutdown.get(2, TimeUnit.SECONDS);
            disconnect.get(2, TimeUnit.SECONDS);

            assertTrue(handle.decision() == QueueDecision.LIMITER_UNAVAILABLE
                    || handle.decision() == QueueDecision.CANCELLED);
            assertEquals(1, handle.terminalTransitions());
            assertEquals(1, dispatches.get());
        } finally {
            racers.shutdownNow();
        }
    }

    @ParameterizedTest
    @MethodSource("rejectedResponses")
    void rejectedAdmissionUsesStableHttpContract(
            RateLimitAdmission.RejectReason reason,
            int expectedStatus,
            String expectedCode,
            String expectedRetryAfter) throws Exception {
        Fixture fixture = fixture(new RateLimitAdmission.Rejected(reason));

        fixture.filter().doFilter(
                fixture.request(), fixture.response(), fixture.chain());

        assertEquals(expectedStatus, fixture.response().getStatus());
        assertEquals(expectedRetryAfter,
                fixture.response().getHeader("Retry-After"));
        assertEquals(expectedCode, writtenCode(fixture.objectMapper()));
        verifyNoInteractions(fixture.chain());
    }

    private static Stream<Arguments> rejectedResponses() {
        return Stream.of(
                Arguments.of(
                        RateLimitAdmission.RejectReason.APPLICATION_QUEUE_FULL,
                        429, "APPLICATION_RATE_QUEUE_FULL", "1"),
                Arguments.of(
                        RateLimitAdmission.RejectReason.GLOBAL_QUEUE_FULL,
                        503, "RATE_LIMIT_GLOBAL_QUEUE_FULL", null),
                Arguments.of(
                        RateLimitAdmission.RejectReason.STATE_CAPACITY_REACHED,
                        503, "RATE_LIMIT_STATE_CAPACITY_REACHED", null),
                Arguments.of(
                        RateLimitAdmission.RejectReason.LIMITER_UNAVAILABLE,
                        503, "RATE_LIMITER_UNAVAILABLE", null));
    }

    @SuppressWarnings("rawtypes")
    private static String writtenCode(ObjectMapper objectMapper) throws Exception {
        var captor = org.mockito.ArgumentCaptor.forClass(ApiResponse.class);
        verify(objectMapper).writeValueAsString(captor.capture());
        return captor.getValue().code();
    }

    private static MockAsyncContext asyncContext(Fixture fixture) {
        return (MockAsyncContext) fixture.request().getAsyncContext();
    }

    private static AsyncListener asyncListener(MockAsyncContext asyncContext) {
        assertEquals(1, asyncContext.getListeners().size());
        return asyncContext.getListeners().getFirst();
    }

    private static AtomicInteger observeDispatches(MockAsyncContext asyncContext) {
        AtomicInteger dispatches = new AtomicInteger();
        asyncContext.addDispatchHandler(dispatches::incrementAndGet);
        return dispatches;
    }

    private static Fixture fixture(RateLimitAdmission admission) throws Exception {
        ApplicationRateLimiter limiter = mock(ApplicationRateLimiter.class);
        when(limiter.acquire(20L)).thenReturn(admission);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        ApplicationRateFilter filter = new ApplicationRateFilter(
                limiter, new MachineIdentityResolver(), objectMapper);
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/v1/utils/uuid");
        request.setAsyncSupported(true);
        request.setAttribute(MachineIdentityResolver.ATTRIBUTE_NAME,
                new ApiKeyIdentity(10L, 20L));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        return new Fixture(filter, limiter, objectMapper, request, response, chain);
    }

    private record Fixture(
            ApplicationRateFilter filter,
            ApplicationRateLimiter limiter,
            ObjectMapper objectMapper,
            MockHttpServletRequest request,
            MockHttpServletResponse response,
            FilterChain chain) {
    }

    private static final class TestWaitHandle
            implements RateLimitAdmission.WaitHandle {
        private final CompletableFuture<QueueDecision> completion =
                new CompletableFuture<>();
        private final AtomicReference<QueueDecision> decision =
                new AtomicReference<>();
        private final AtomicInteger terminalTransitions =
                new AtomicInteger();

        @Override
        public CompletionStage<QueueDecision> completion() {
            return completion;
        }

        @Override
        public boolean cancel() {
            return decide(QueueDecision.CANCELLED);
        }

        private boolean decide(QueueDecision terminalDecision) {
            if (!decision.compareAndSet(null, terminalDecision)) {
                return false;
            }
            terminalTransitions.incrementAndGet();
            completion.complete(terminalDecision);
            return true;
        }

        private QueueDecision decision() {
            return decision.get();
        }

        private int terminalTransitions() {
            return terminalTransitions.get();
        }
    }
}
