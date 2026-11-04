package com.nexus.webhook.dispatch;

import com.nexus.webhook.mapper.WebhookOutboxMapper;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Worker starts one session on entry and closes it on every exit. */
@Slf4j
public final class WebhookLeaseManager implements AutoCloseable {
    private final WebhookOutboxMapper mapper;
    private final LeaseScheduler scheduler;
    private final LongSupplier nanoTime;
    private final long ttlMicros;
    private final Duration interval;
    private final Set<Session> sessions = new HashSet<>();
    private boolean closed;
    private final TransactionTemplate completionTransaction;

    /** Compatibility constructor: wall clock does not determine lease timing. */
    public WebhookLeaseManager(WebhookOutboxMapper mapper, LeaseScheduler scheduler, Clock clock,
                               Duration ttl, Duration interval, TransactionTemplate completionTransaction) {
        this(mapper, scheduler, ttl, interval, completionTransaction, System::nanoTime);
        Objects.requireNonNull(clock);
    }

    public WebhookLeaseManager(WebhookOutboxMapper mapper, LeaseScheduler scheduler,
                        Duration ttl, Duration interval, TransactionTemplate completionTransaction, LongSupplier nanoTime) {
        this.completionTransaction = Objects.requireNonNull(completionTransaction);
        int propagation = completionTransaction.getPropagationBehavior();
        if (propagation != TransactionDefinition.PROPAGATION_REQUIRED
                && propagation != TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
            throw new IllegalArgumentException("lease operations require a transaction");
        }
        this.mapper = Objects.requireNonNull(mapper);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.ttlMicros = LeaseTime.positiveMicros(Objects.requireNonNull(ttl));
        this.interval = Objects.requireNonNull(interval);
        interval.toNanos();
        if (ttl.isNegative() || ttl.isZero() || interval.isNegative() || interval.isZero()
                || interval.compareTo(ttl) >= 0) throw new IllegalArgumentException("invalid lease durations");
    }

    public synchronized Session start(ClaimedWebhookEvent event) {
        if (closed) throw new IllegalStateException("lease manager is closed");
        Session session = new Session(Objects.requireNonNull(event));
        sessions.add(session);
        try { session.armDeadline(); session.bindFuture(scheduler.schedule(session::renew, interval)); }
        catch (RuntimeException | Error e) { session.close(); throw e; }
        return session;
    }

    private synchronized void remove(Session session) { sessions.remove(session); }

    @Override public void close() {
        Session[] pending;
        synchronized (this) {
            closed = true;
            pending = sessions.toArray(Session[]::new);
        }
        // Mark all sessions first: one blocked database call must not delay cancellation of others.
        boolean[] changed = new boolean[pending.length];
        for (int i = 0; i < pending.length; i++) changed[i] = pending[i].requestClose();
        for (int i = 0; i < pending.length; i++) pending[i].finishClose(changed[i]);
    }

    public final class Session implements AutoCloseable {
        private final ClaimedWebhookEvent event;
        private enum State { ACTIVE, RENEWING, RENEWING_CALL, WRITING, WRITING_CLOSING, LEASE_LOST, LEASE_UNCERTAIN, COMPLETED }
        private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
        private final CancellationToken cancellation = new CancellationToken();
        private volatile Budget budget;
        private record Budget(long started, long nanos) {}
        private volatile ScheduledFuture<?> future;
        private volatile ScheduledFuture<?> deadline;

        private Session(ClaimedWebhookEvent event) {
            this.event = event;
            this.budget = new Budget(event.budgetStartedNanos(), event.budgetNanos());
        }

        private long remaining() {
            Budget current = budget;
            long elapsed = nanoTime.getAsLong() - current.started();
            return elapsed < 0 || elapsed >= current.nanos() ? 0 : current.nanos() - elapsed;
        }

        private void armDeadline() {
            try {
                long remaining = remaining();
                if (remaining == 0) { finishClose(requestClose()); return; }
                ScheduledFuture<?> next = scheduler.deadline(() -> finishClose(requestClose()), remaining);
                ScheduledFuture<?> previous = deadline;
                deadline = next;
                if (previous != null) previous.cancel(false);
                if (status() != LeaseStatus.ACTIVE) next.cancel(false);
            } catch (RuntimeException | Error e) {
                finishClose(requestClose());
                throw e;
            }
        }

        private TransactionTemplate boundedTransaction(long remaining) {
            long seconds = (remaining - 1) / 1_000_000_000L;
            if (seconds < 1) return null;
            TransactionTemplate bounded = new TransactionTemplate(
                    Objects.requireNonNull(completionTransaction.getTransactionManager()), completionTransaction);
            int configured = completionTransaction.getTimeout();
            int timeout = (int) Math.min(seconds, Integer.MAX_VALUE);
            bounded.setTimeout(configured > 0 ? Math.min(configured, timeout) : timeout);
            return bounded;
        }
        public LeaseStatus status() {
            return switch (state.get()) {
                case ACTIVE, RENEWING, RENEWING_CALL, WRITING -> LeaseStatus.ACTIVE;
                case WRITING_CLOSING, LEASE_UNCERTAIN -> LeaseStatus.LEASE_UNCERTAIN;
                case LEASE_LOST -> LeaseStatus.LEASE_LOST;
                case COMPLETED -> LeaseStatus.COMPLETED;
            };
        }
        public CancellationToken cancellation() { return cancellation; }
        public boolean usable() { return status() == LeaseStatus.ACTIVE && !cancellation.isCancelled() && remaining() > 0; }

        void bindFuture(ScheduledFuture<?> scheduled) {
            future = Objects.requireNonNull(scheduled);
            if (status() != LeaseStatus.ACTIVE) scheduled.cancel(false);
        }

        // The callback must execute the final token-guarded database update synchronously and return its row count.
        // It must not run inside an outer transaction: a later rollback would invalidate COMPLETED.
        public LeaseStatus complete(IntSupplier writeback) {
            Objects.requireNonNull(writeback);
            boolean cancel = false;
            Throwable failure = null;
            synchronized (this) {
                if (status() != LeaseStatus.ACTIVE) return status();
                boolean expired;
                try { expired = remaining() <= 1_000_000_000L; }
                catch (RuntimeException | Error e) {
                    cancel = transition(LeaseStatus.LEASE_UNCERTAIN);
                    expired = true;
                    failure = e;
                }
                if (expired) {
                    cancel |= transition(LeaseStatus.LEASE_UNCERTAIN);
                } else if (status() != LeaseStatus.ACTIVE) {
                    return status();
                } else if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
                    throw new IllegalStateException("writeback cannot run in an outer transaction");
                } else {
                    // This CAS is the arbitration point: close either wins before writing starts,
                    // or requests cancellation while the committed write result remains authoritative.
                    if (!state.compareAndSet(State.ACTIVE, State.WRITING)) return status();
                    try {
                        TransactionTemplate bounded = boundedTransaction(remaining());
                        if (bounded == null) throw new IllegalStateException("lease budget exhausted");
                        int rows = bounded.execute(tx -> {
                                    if (mapper.lockForCompletion(event.event().getId()) == null) return 0;
                                    return writeback.getAsInt();
                                });
                        cancel = finishWrite(rows == 1 ? State.COMPLETED : State.LEASE_LOST);
                    } catch (RuntimeException | Error e) {
                        cancel = finishWrite(State.LEASE_UNCERTAIN);
                        failure = e;
                    }
                }
            }
            finish(cancel);
            if (failure instanceof RuntimeException e) throw e;
            if (failure instanceof Error e) throw e;
            return status();
        }

        void renew() {
            boolean cancel = false;
            try {
                synchronized (this) {
                    if (!state.compareAndSet(State.ACTIVE, State.RENEWING)) return;
                    if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                        throw new IllegalStateException("renewal cannot run in an outer transaction");
                    for (int attempt = 0; attempt < 3; attempt++) {
                        if (state.get() != State.RENEWING) break;
                        long remaining = remaining();
                        TransactionTemplate bounded = boundedTransaction(remaining);
                        if (bounded == null) { cancel = endRenewal(LeaseStatus.LEASE_UNCERTAIN); break; }
                        long started = nanoTime.getAsLong();
                        // Admission of this database attempt is atomic with close. A close after
                        // this CAS may cancel locally but cannot undo an already admitted call.
                        if (!state.compareAndSet(State.RENEWING, State.RENEWING_CALL)) break;
                        try {
                            LeaseSnapshot snapshot = bounded.execute(tx -> {
                                if (mapper.lockForCompletion(event.event().getId()) == null) return null;
                                if (mapper.renewLease(event.event().getId(), event.claimToken(), ttlMicros) != 1) return null;
                                return Objects.requireNonNull(mapper.selectLeaseSnapshot(event.event().getId(), event.claimToken()));
                            });
                            if (snapshot != null) {
                                long renewedBudget = LeaseTime.budgetNanos(snapshot);
                                budget = new Budget(started, renewedBudget);
                                if (remaining() == 0)
                                    cancel = endRenewal(LeaseStatus.LEASE_UNCERTAIN);
                                else if (state.compareAndSet(State.RENEWING_CALL, State.ACTIVE)) armDeadline();
                            } else cancel = endRenewal(LeaseStatus.LEASE_LOST);
                            break;
                        } catch (RuntimeException e) {
                            log.warn("Webhook lease renewal failed, eventId={}, errorType={}",
                                    event.event().getEventId(), e.getClass().getSimpleName());
                            if (attempt == 2) cancel = endRenewal(LeaseStatus.LEASE_UNCERTAIN);
                            else if (!state.compareAndSet(State.RENEWING_CALL, State.RENEWING)) break;
                        }
                    }
                }
            } catch (Throwable e) {
                log.warn("Webhook lease scheduling failed, errorType={}", e.getClass().getSimpleName());
                cancel = endRenewal(LeaseStatus.LEASE_UNCERTAIN);
                finish(cancel);
                if (e instanceof VirtualMachineError fatal) throw fatal;
                if (e instanceof ThreadDeath fatal) throw fatal;
                return;
            }
            finish(cancel);
        }

        private boolean endRenewal(LeaseStatus next) {
            State result = State.valueOf(next.name());
            while (true) {
                State current = state.get();
                if (current != State.RENEWING && current != State.RENEWING_CALL) return false;
                if (state.compareAndSet(current, result)) {
                    if (future != null) future.cancel(false);
                    return true;
                }
            }
        }

        private boolean transition(LeaseStatus next) {
            if (!state.compareAndSet(State.ACTIVE, State.valueOf(next.name()))) return false;
            if (future != null) future.cancel(false);
            return true;
        }

        private boolean finishWrite(State result) {
            State before = state.getAndSet(result);
            if (before != State.WRITING && before != State.WRITING_CLOSING)
                throw new IllegalStateException("invalid completion state");
            if (future != null) future.cancel(false);
            return true;
        }

        private boolean requestClose() {
            while (true) {
                State current = state.get();
                if (current == State.ACTIVE) {
                    if (transition(LeaseStatus.LEASE_UNCERTAIN)) return true;
                } else if (current == State.RENEWING || current == State.RENEWING_CALL) {
                    if (state.compareAndSet(current, State.LEASE_UNCERTAIN)) {
                        if (future != null) future.cancel(false);
                        return true;
                    }
                } else if (current == State.WRITING) {
                    if (state.compareAndSet(current, State.WRITING_CLOSING)) {
                        if (future != null) future.cancel(false);
                        return true;
                    }
                } else return false;
            }
        }

        private void finish(boolean transitioned) {
            if (!transitioned) return;
            if (deadline != null) deadline.cancel(false);
            remove(this);
            if (status() != LeaseStatus.COMPLETED) cancellation.cancel();
        }

        private void finishClose(boolean requested) {
            if (!requested) return;
            if (deadline != null) deadline.cancel(false);
            remove(this);
            cancellation.cancel();
        }

        @Override public void close() {
            finishClose(requestClose());
        }
    }
}
