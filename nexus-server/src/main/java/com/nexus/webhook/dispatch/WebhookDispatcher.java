package com.nexus.webhook.dispatch;

import com.nexus.webhook.entity.WebhookOutbox;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Explicitly assembled when a worker and scheduler are available. */
@Slf4j
public class WebhookDispatcher {
    private final WebhookOutboxMapper mapper;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolExecutor executor;
    private final Semaphore semaphore;
    private final long ttlMicros;
    private final LongSupplier nanoTime;
    private final WebhookWorker worker;
    private boolean accepting = true;

    /** Compatibility constructor: wall clock does not determine lease timing. */
    public WebhookDispatcher(WebhookOutboxMapper mapper, TransactionTemplate transactionTemplate,
                             ThreadPoolExecutor executor, Semaphore semaphore, Clock clock,
                             Duration leaseDuration, WebhookWorker worker) {
        this(mapper, transactionTemplate, executor, semaphore, leaseDuration, worker, System::nanoTime);
        Objects.requireNonNull(clock);
    }

    WebhookDispatcher(WebhookOutboxMapper mapper, TransactionTemplate transactionTemplate,
                      ThreadPoolExecutor executor, Semaphore semaphore, Duration leaseDuration,
                      WebhookWorker worker, LongSupplier nanoTime) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
        this.executor = Objects.requireNonNull(executor);
        this.semaphore = Objects.requireNonNull(semaphore);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.ttlMicros = LeaseTime.positiveMicros(Objects.requireNonNull(leaseDuration));
        this.worker = Objects.requireNonNull(worker);
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        validateConfiguration();
    }

    private void validateConfiguration() {
        if (!(executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy)) {
            throw new IllegalArgumentException("executor must use AbortPolicy");
        }
        int propagation = transactionTemplate.getPropagationBehavior();
        if (propagation != TransactionDefinition.PROPAGATION_REQUIRED &&
                propagation != TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
            throw new IllegalArgumentException("claim requires a transaction");
        }
    }

    public void dispatchDueEvents() {
        validateConfiguration();
        // Never hand off work while an enclosing transaction can still roll back.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("dispatch cannot run inside a transaction");
        }
        int reserved = 0;
        while (semaphore.tryAcquire()) {
            reserved++;
        }
        if (reserved == 0) return;

        List<ClaimedWebhookEvent> claimed;
        try {
            int limit = reserved;
            long started = nanoTime.getAsLong();
            claimed = Objects.requireNonNull(transactionTemplate.execute(status -> claim(limit, started)));
        } catch (RuntimeException | Error exception) {
            semaphore.release(reserved);
            throw exception;
        }
        semaphore.release(reserved - claimed.size());
        for (ClaimedWebhookEvent event : claimed) submit(event);
    }

    private List<ClaimedWebhookEvent> claim(int limit, long started) {
        List<ClaimedWebhookEvent> claimed = new ArrayList<>();
        for (WebhookOutbox event : mapper.selectDueForUpdate(limit)) {
            String token = UUID.randomUUID().toString();
            if (mapper.claim(event.getId(), token, ttlMicros) == 1) {
                LeaseSnapshot snapshot = Objects.requireNonNull(mapper.selectLeaseSnapshot(event.getId(), token));
                LocalDateTime leaseExpiresAt = snapshot.leaseExpiresAt();
                event.setStatus(1);
                event.setClaimToken(token);
                event.setLeaseExpiresAt(leaseExpiresAt);
                claimed.add(new ClaimedWebhookEvent(event, token, leaseExpiresAt, started, LeaseTime.budgetNanos(snapshot)));
            }
        }
        return claimed;
    }

    /** Prevent further executor submissions, including from an in-flight database claim. */
    public synchronized void stopAccepting() { accepting = false; }

    private void submit(ClaimedWebhookEvent event) {
        AtomicBoolean started = new AtomicBoolean();
        try {
            synchronized (this) {
                if (!accepting) throw new java.util.concurrent.RejectedExecutionException("dispatcher stopped");
                executor.execute(() -> {
                started.set(true);
                try {
                    worker.process(event);
                } catch (Exception exception) {
                    log.error("Webhook worker execution failed, eventId={}, errorType={}",
                            event.event().getEventId(), exception.getClass().getSimpleName());
                } finally {
                    semaphore.release();
                }
                });
            }
        } catch (RuntimeException exception) {
            log.error("Webhook worker submission failed, eventId={}, errorType={}",
                    event.event().getEventId(), exception.getClass().getSimpleName());
            // A custom executor may execute inline and then throw; the task owns its permit once started.
            if (!started.get()) releaseRejectedClaim(event);
        }
    }

    private void releaseRejectedClaim(ClaimedWebhookEvent event) {
        try {
            int rows = mapper.releaseClaim(event.event().getId(), event.claimToken());
            if (rows != 1) {
                log.warn("Failed to release rejected webhook claim, eventId={}, rows={}", event.event().getEventId(), rows);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to release rejected webhook claim, eventId={}, errorType={}",
                    event.event().getEventId(), exception.getClass().getSimpleName());
        } finally {
            semaphore.release();
        }
    }
}
