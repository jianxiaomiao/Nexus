package com.nexus.webhook.dispatch;

import com.nexus.webhook.mapper.WebhookOutboxMapper;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits an admission before HTTP. A crash after commit may count an unsent request.
 * The future worker must check its lease session and cancellation immediately before and after
 * preparation, bind cancellation to the HTTP operation, and use the session for final writeback.
 * A committed permit cannot eliminate a cancellation race after the final local check.
 */
public final class WebhookAttemptPreparer {
    public enum AttemptType { INITIAL, ORDINARY_RETRY, SECRET_REFRESH }
    /** Counts committed admissions, including initial, ordinary retry and secret refresh;
     * an admission does not guarantee a network start or completion.
     * Ordinary retries equal count minus the initial admission and the refresh flag.
     */
    public record Snapshot(long attemptCount, boolean secretRefreshAttempted) {
        public Snapshot {
            if (attemptCount < 0 || attemptCount > 0xffff_ffffL || (attemptCount < 2 && secretRefreshAttempted))
                throw new IllegalArgumentException("invalid attempt snapshot");
        }
        public long ordinaryRetriesUsed() { return attemptCount == 0 ? 0 : attemptCount - 1 - (secretRefreshAttempted ? 1 : 0); }
    }
    @FunctionalInterface
    public interface HttpOperation<T> { T send(Snapshot committedSnapshot) throws IOException; }
    public record AttemptResult<T>(Snapshot snapshot, T response) {}

    private final WebhookOutboxMapper mapper;
    private final TransactionTemplate transaction;
    private final int maxOrdinaryRetries;

    public WebhookAttemptPreparer(WebhookOutboxMapper mapper, TransactionTemplate transaction, int maxOrdinaryRetries) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transaction = Objects.requireNonNull(transaction);
        requireTransaction();
        if (maxOrdinaryRetries < 0) throw new IllegalArgumentException("negative retries");
        this.maxOrdinaryRetries = maxOrdinaryRetries;
    }

    private void requireTransaction() {
        int propagation = transaction.getPropagationBehavior();
        if (propagation != TransactionDefinition.PROPAGATION_REQUIRED && propagation != TransactionDefinition.PROPAGATION_REQUIRES_NEW)
            throw new IllegalStateException("attempt preparation requires a transaction");
    }

    /**
     * Prepare and pin the HTTP request and decrypt the secret before calling; check session state around preparation.
     * The callback must recheck cancellation before sending and bind cancellation to the HTTP call.
     * It runs once, only after the admission commits; failures never reverse the committed count.
     * Send failures carry the committed snapshot; preparation and commit failures propagate unchanged.
     */
    public <T> Optional<AttemptResult<T>> prepareAndSend(long id, String token, Snapshot expected,
            AttemptType type, HttpOperation<T> operation) throws IOException {
        return prepareAndSend(id, token, expected, type, null, null, operation);
    }

    public <T> Optional<AttemptResult<T>> prepareAndSend(long id, String token, Snapshot expected,
            AttemptType type, String url, Long secretId, HttpOperation<T> operation) throws IOException {
        Objects.requireNonNull(operation);
        Optional<Snapshot> permit = prepare(id, token, expected, type, url, secretId);
        if (permit.isEmpty()) return Optional.empty();
        Snapshot committed = permit.orElseThrow();
        try {
            return Optional.of(new AttemptResult<>(committed, operation.send(committed)));
        } catch (IOException | RuntimeException failure) {
            throw new WebhookSendException(committed, failure);
        }
    }

    /** Each admitted HTTP attempt needs a newly prevalidated and pinned request. */
    public Optional<AttemptResult<WebhookHttpResponse>> prepareAndSend(long id, String token, Snapshot expected,
            AttemptType type, WebhookHttpClient.PreparedPost prepared) throws IOException {
        Objects.requireNonNull(prepared);
        return prepareAndSend(id, token, expected, type, snapshot -> prepared.send());
    }

    /** Caller retains its prior snapshot; never replace it with a fresh read to retry a rejected admission. */
    public Optional<Snapshot> prepare(long id, String token, Snapshot expected, AttemptType type) {
        return prepare(id, token, expected, type, null, null);
    }

    private Optional<Snapshot> prepare(long id, String token, Snapshot expected, AttemptType type, String url, Long secretId) {
        Objects.requireNonNull(token); Objects.requireNonNull(expected); Objects.requireNonNull(type);
        if ((url == null) != (secretId == null)) throw new IllegalArgumentException("incomplete delivery configuration");
        requireTransaction();
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("attempt preparation cannot run in an outer transaction");
        boolean allowed = switch (type) {
            case INITIAL -> expected.attemptCount() == 0;
            case ORDINARY_RETRY -> expected.attemptCount() > 0 && expected.ordinaryRetriesUsed() < maxOrdinaryRetries;
            case SECRET_REFRESH -> expected.attemptCount() > 0 && !expected.secretRefreshAttempted();
        };
        if (!allowed || expected.attemptCount() == 0xffff_ffffL) return Optional.empty();
        boolean refresh = expected.secretRefreshAttempted() || type == AttemptType.SECRET_REFRESH;
        Boolean updated = transaction.execute(status -> {
            if (mapper.lockForCompletion(id) == null) return false;
            if (mapper.prepareAttempt(id, token, expected.attemptCount(), expected.secretRefreshAttempted(), refresh) != 1) return false;
            if (url != null && mapper.recordDeliveryConfig(id, token, url, secretId) != 1)
                throw new IllegalStateException("delivery configuration recording failed");
            return true;
        });
        return Boolean.TRUE.equals(updated) ? Optional.of(new Snapshot(expected.attemptCount() + 1, refresh)) : Optional.empty();
    }
}
