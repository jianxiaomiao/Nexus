package com.nexus.openapi.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import static com.nexus.openapi.ratelimit.RateLimitAdmission.RejectReason.GLOBAL_QUEUE_FULL;

public final class ApplicationRateLimiter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ApplicationRateLimiter.class);

    private final ConcurrentHashMap<Long, ApplicationRateState> states =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final Semaphore globalWaitingSlots;

    private final int maxApplicationWaiters;
    private final int maxApplicationStates;
    private final long intervalNanos;
    private final long waitTimeoutNanos;

    private final LongSupplier nanoTime;
    private final AtomicBoolean closing = new AtomicBoolean();

    private final Object stateCreationMonitor = new Object();

    private static final long IDLE_STATE_TTL_NANOS =
            Duration.ofSeconds(10).toNanos();

    private static final long CLEANUP_INTERVAL_NANOS =
            Duration.ofSeconds(5).toNanos();

    private final Object lifecycleMonitor = new Object();

    private ScheduledFuture<?> cleanupTask;

    public ApplicationRateLimiter(
            ScheduledExecutorService scheduler,
            int maxApplicationWaiters,
            int maxGlobalWaiters,
            int maxApplicationStates,
            Duration interval,
            Duration waitTimeout,
            LongSupplier nanoTime) {

        this.scheduler = Objects.requireNonNull(scheduler);
        this.nanoTime = Objects.requireNonNull(nanoTime);

        this.maxApplicationWaiters = maxApplicationWaiters;
        this.maxApplicationStates = maxApplicationStates;
        this.intervalNanos = interval.toNanos();
        this.waitTimeoutNanos = waitTimeout.toNanos();

        this.globalWaitingSlots =
                new Semaphore(maxGlobalWaiters);
    }

    public RateLimitAdmission acquire(Long applicationId) {
        //校验applicationId
        if (applicationId == null || applicationId <= 0) {
            throw new InvalidRateLimitApplicationIdException();
        }

        if (closing.get()) {
            return new RateLimitAdmission.Rejected(
                    RateLimitAdmission.RejectReason.LIMITER_UNAVAILABLE);
        }

        //查找或创建ApplicationRateState
        ApplicationRateState state =
                findOrCreateAndLockState(applicationId);


        if (state == null) {
            RateLimitAdmission.RejectReason reason =
                    closing.get()
                            ? RateLimitAdmission.RejectReason.LIMITER_UNAVAILABLE
                            : RateLimitAdmission.RejectReason.STATE_CAPACITY_REACHED;

            return new RateLimitAdmission.Rejected(reason);
        }
        try {
            if (closing.get()) {
                return new RateLimitAdmission.Rejected(
                        RateLimitAdmission.RejectReason.LIMITER_UNAVAILABLE);
            }

            long now = nanoTime.getAsLong();

            if (state.waiters.isEmpty()
                    && now >= state.nextPermitNanos) {

                state.nextPermitNanos =
                        Math.addExact(now, intervalNanos);

                state.lastTouchedNanos = now;

                return new RateLimitAdmission.Allowed();
            }

            if (state.waiters.size() >= maxApplicationWaiters) {
                return new RateLimitAdmission.Rejected(
                        RateLimitAdmission.RejectReason.APPLICATION_QUEUE_FULL);
            }

            if (!globalWaitingSlots.tryAcquire()) {
                return new RateLimitAdmission.Rejected(GLOBAL_QUEUE_FULL);
            }


            long deadlineNanos =
                    Math.addExact(now, waitTimeoutNanos);
            //计算delay,创建Waiter;
            Waiter waiter = new Waiter(deadlineNanos);
            //加入队尾;
            state.waiters.addLast(waiter);
            state.lastTouchedNanos = now;

            try {
                ensureDrainScheduled(applicationId, state, now);
            } catch (RejectedExecutionException exception) {
                state.waiters.remove(waiter);
                globalWaitingSlots.release();

                return new RateLimitAdmission.Rejected(
                        RateLimitAdmission.RejectReason.LIMITER_UNAVAILABLE);
            }

            return new RateLimitAdmission.Queued(
                    new WaitHandleImpl(state, waiter));
        } finally {
            state.lock.unlock();
        }
    }

    private ApplicationRateState findOrCreateState(long applicationId) {
        ApplicationRateState existing = states.get(applicationId);

        if (existing != null) {
            return existing;
        }

        return automaticCreate(applicationId);
    }

    private ApplicationRateState automaticCreate(long applicationId) {
        synchronized (stateCreationMonitor) {
            if (closing.get()) {
                return null;
            }

            // 第一次查找后，等待创建锁期间，其他线程可能已经创建完成
            ApplicationRateState existing = states.get(applicationId);
            if (existing != null) {
                return existing;
            }

            if (states.size() >= maxApplicationStates) {
                return null;
            }

            ApplicationRateState created = new ApplicationRateState();
            states.put(applicationId, created);
            return created;
        }
    }

    private void ensureDrainScheduled(
            long applicationId,
            ApplicationRateState state,
            long now) {

        if (!state.lock.isHeldByCurrentThread()) {
            throw new IllegalStateException(
                    "state.lock must be held");
        }

        if (state.drainTask != null
                || state.waiters.isEmpty()) {
            return;
        }

        Waiter head = state.waiters.getFirst();

        /*
         * 恰好到达 deadline 仍允许，因此只有 deadline 之后才能判定超时。
         * 若许可时刻更晚，下一次唤醒安排在 deadline 后 1ns，避免在
         * deadline 本身不断提交 0 延迟任务。
         */
        long timeoutWakeUpNanos = head.deadlineNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : head.deadlineNanos + 1L;

        long wakeUpNanos = Math.min(
                state.nextPermitNanos,
                timeoutWakeUpNanos);

        long delayNanos = wakeUpNanos <= now
                ? 0L
                : wakeUpNanos - now;

        state.drainTask = scheduler.schedule(
                () -> drain(applicationId, state),
                delayNanos,
                TimeUnit.NANOSECONDS);
    }

    private void drain(
            long applicationId,
            ApplicationRateState state) {

        List<Waiter> timedOutWaiters = new ArrayList<>();
        Waiter allowedWaiter = null;

        List<Waiter> unavailableWaiters =
                new ArrayList<>();

        ReentrantLock lock = state.lock;
        lock.lock();

        try {
            // 当前这次调度任务已经开始执行
            state.drainTask = null;

            if (closing.get()) {
                unavailableWaiters =
                        removeAllWaitersAsUnavailable(state);
            } else {
                long now = nanoTime.getAsLong();

                // 清理所有已经超时的队首
                while (!state.waiters.isEmpty()) {
                    Waiter head = state.waiters.getFirst();

                    if (now <= head.deadlineNanos) {
                        break;
                    }

                    state.waiters.removeFirst();
                    head.status = WaiterStatus.TIMED_OUT;
                    timedOutWaiters.add(head);

                    globalWaitingSlots.release();
                    state.lastTouchedNanos = now;
                }

                // 当前限速时间已经到达，最多放行一个
                if (!state.waiters.isEmpty()
                        && now >= state.nextPermitNanos) {

                    allowedWaiter = state.waiters.removeFirst();
                    allowedWaiter.status = WaiterStatus.ALLOWED;

                    globalWaitingSlots.release();

                    state.nextPermitNanos =
                            Math.addExact(now, intervalNanos);

                    state.lastTouchedNanos = now;
                }

                // 队列还有请求，安排下一次唤醒
                if (!state.waiters.isEmpty()) {
                    try {
                        ensureDrainScheduled(
                                applicationId,
                                state,
                                now);
                    } catch (RejectedExecutionException exception) {
                        unavailableWaiters.addAll(
                                removeAllWaitersAsUnavailable(state));
                    }
                }
            }
        } finally {
            lock.unlock();
        }

        for (Waiter waiter : unavailableWaiters) {
            waiter.result.complete(
                    QueueDecision.LIMITER_UNAVAILABLE);
        }

        // 完成 Future 时已经不再持有 state.lock
        for (Waiter timedOutWaiter : timedOutWaiters) {
            timedOutWaiter.result.complete(
                    QueueDecision.TIMED_OUT);
        }

        if (allowedWaiter != null) {
            allowedWaiter.result.complete(
                    QueueDecision.ALLOWED);
        }
    }

    private boolean cancelWaiter(
            ApplicationRateState state,
            Waiter waiter) {

        state.lock.lock();
        try {
            if (waiter.status != WaiterStatus.WAITING) {
                return false;
            }

            boolean removed = state.waiters.remove(waiter);
            if (!removed) {
                return false;
            }

            waiter.status = WaiterStatus.CANCELLED;
            state.lastTouchedNanos = nanoTime.getAsLong();

            globalWaitingSlots.release();
        } finally {
            state.lock.unlock();
        }

        waiter.result.complete(QueueDecision.CANCELLED);
        return true;
    }

    private final class WaitHandleImpl
            implements RateLimitAdmission.WaitHandle {

        private final ApplicationRateState state;
        private final Waiter waiter;

        private WaitHandleImpl(
                ApplicationRateState state,
                Waiter waiter) {
            this.state = state;
            this.waiter = waiter;
        }

        @Override
        public CompletionStage<QueueDecision> completion() {
            return waiter.result;
        }

        @Override
        public boolean cancel() {
            return cancelWaiter(state, waiter);
        }
    }

    private List<Waiter> removeAllWaitersAsUnavailable(
            ApplicationRateState state) {

        if (!state.lock.isHeldByCurrentThread()) {
            throw new IllegalStateException(
                    "state.lock must be held");
        }

        List<Waiter> unavailableWaiters =
                new ArrayList<>(state.waiters.size());

        while (!state.waiters.isEmpty()) {
            Waiter waiter = state.waiters.removeFirst();

            waiter.status =
                    WaiterStatus.LIMITER_UNAVAILABLE;

            globalWaitingSlots.release();
            unavailableWaiters.add(waiter);
        }

        return unavailableWaiters;
    }

    @Override
    public void close() {
        // 只有第一个调用 close() 的线程负责清理
        if (!closing.compareAndSet(false, true)) {
            return;
        }

        ScheduledFuture<?> task;

        synchronized (lifecycleMonitor) {
            task = cleanupTask;
            cleanupTask = null;
        }

        if (task != null) {
            task.cancel(false);
        }

        List<ApplicationRateState> stateSnapshot;

        /*
         * 与状态创建互斥。
         * 等待正在进行的创建完成，然后取得完整快照。
         */
        synchronized (stateCreationMonitor) {
            stateSnapshot = List.copyOf(states.values());
        }

        for (ApplicationRateState state : stateSnapshot) {
            List<Waiter> unavailableWaiters;

            state.lock.lock();
            try {
                if (state.drainTask != null) {
                    /*
                     * false 表示不强行中断已经开始运行的任务。
                     * 已经运行的 drain 会通过 closing 再次判断。
                     */
                    state.drainTask.cancel(false);
                    state.drainTask = null;
                }

                unavailableWaiters =
                        removeAllWaitersAsUnavailable(state);
            } finally {
                state.lock.unlock();
            }

            // Future 必须在锁外完成
            for (Waiter waiter : unavailableWaiters) {
                waiter.result.complete(
                        QueueDecision.LIMITER_UNAVAILABLE);
            }
        }
    }

    public void start() {
        synchronized (lifecycleMonitor) {
            if (closing.get()) {
                throw new IllegalStateException(
                        "rate limiter is already closing");
            }

            if (cleanupTask != null) {
                return;
            }

            cleanupTask = scheduler.scheduleWithFixedDelay(
                    this::cleanupIdleStatesSafely,
                    CLEANUP_INTERVAL_NANOS,
                    CLEANUP_INTERVAL_NANOS,
                    TimeUnit.NANOSECONDS);
        }
    }
    private void cleanupIdleStatesSafely() {
        try {
            cleanupIdleStates();
        } catch (RuntimeException exception) {
            log.error("清理空闲限流状态失败", exception);
        }
    }
    private void cleanupIdleStates() {
        if (closing.get()) {
            return;
        }

        long now = nanoTime.getAsLong();

        for (Map.Entry<Long, ApplicationRateState> entry
                : states.entrySet()) {

            ApplicationRateState state = entry.getValue();

            if (!state.lock.tryLock()) {
                continue;
            }

            try {
                boolean idle =
                        state.waiters.isEmpty()
                                && state.drainTask == null
                                && now >= state.nextPermitNanos
                                && now - state.lastTouchedNanos
                                >= IDLE_STATE_TTL_NANOS;

                if (idle
                        && states.remove(entry.getKey(), state)) {
                    state.retired = true;
                }
            } finally {
                state.lock.unlock();
            }
        }
    }

    private ApplicationRateState findOrCreateAndLockState(
            long applicationId) {

        while (true) {
            if (closing.get()) {
                return null;
            }

            ApplicationRateState state =
                    findOrCreateState(applicationId);

            if (state == null) {
                return null;
            }

            state.lock.lock();

            if (!state.retired) {
                // 返回非 null 时，调用线程一定持有 state.lock
                return state;
            }

            // State 已被清理器删除，释放旧锁后重新查找
            state.lock.unlock();
        }
    }
}
