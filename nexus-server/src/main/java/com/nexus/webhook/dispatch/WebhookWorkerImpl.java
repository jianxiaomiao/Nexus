package com.nexus.webhook.dispatch;

import com.nexus.webhook.crypto.EncryptedSecret;
import com.nexus.webhook.crypto.WebhookSecretCrypto;
import com.nexus.webhook.crypto.WebhookSignatureService;
import com.nexus.webhook.entity.ApplicationWebhook;
import com.nexus.webhook.entity.WebhookOutbox;
import com.nexus.webhook.entity.WebhookSecret;
import com.nexus.webhook.mapper.ApplicationWebhookMapper;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import com.nexus.webhook.mapper.WebhookSecretMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.FutureTask;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/** Coordinates one claimed event; admission and completion remain separate short transactions. */
@Slf4j
public final class WebhookWorkerImpl implements WebhookWorker, AutoCloseable {
    private final WebhookLeaseManager leases;
    private final WebhookAttemptPreparer preparer;
    private final WebhookOutboxMapper outbox;
    private final ApplicationWebhookMapper configurations;
    private final WebhookSecretMapper secrets;
    private final WebhookHttpClient http;
    private final WebhookSecretCrypto crypto;
    private final WebhookSignatureService signatures;
    private final ObjectMapper json;
    private final WebhookResponseHandler responses;
    private final WebhookExceptionClassifier classifier;
    private final Clock clock;
    private final Set<WebhookLeaseManager.Session> active = new HashSet<>();
    private boolean closed;

    public WebhookWorkerImpl(WebhookLeaseManager leases, WebhookAttemptPreparer preparer,
            WebhookOutboxMapper outbox, ApplicationWebhookMapper configurations, WebhookSecretMapper secrets,
            WebhookHttpClient http, WebhookSecretCrypto crypto, WebhookSignatureService signatures,
            ObjectMapper json, WebhookResponseHandler responses, WebhookExceptionClassifier classifier, Clock clock) {
        this.leases = leases; this.preparer = preparer; this.outbox = outbox; this.configurations = configurations;
        this.secrets = secrets; this.http = http; this.crypto = crypto; this.signatures = signatures;
        this.json = json; this.responses = responses; this.classifier = classifier; this.clock = clock;
    }

    @Override public void process(ClaimedWebhookEvent claim) {
        WebhookLeaseManager.Session session;
        synchronized (this) {
            if (closed) return;
            session = leases.start(claim);
            active.add(session);
        }
        try {
            run(claim, session);
        } catch (RuntimeException e) {
            // Unknown transaction outcomes must be recovered from the database after lease expiry.
            log.error("Webhook worker stopped, eventId={}, errorType={}", claim.event().getEventId(), e.getClass().getSimpleName());
        } finally {
            session.close();
            synchronized (this) { active.remove(session); }
        }
    }

    private void run(ClaimedWebhookEvent claim, WebhookLeaseManager.Session session) {
        long id = claim.event().getId(); String token = claim.claimToken();
        if (!session.usable()) return;
        WebhookOutbox event = outbox.selectClaimed(id, token);
        if (event == null || !session.usable()) return;
        var snapshot = new WebhookAttemptPreparer.Snapshot(event.getAttemptCount(), event.getSecretRefreshAttempted());
        byte[] payload = WebhookPayload.from(event).toBytes(json);
        WebhookAttemptPreparer.AttemptType type = snapshot.attemptCount() == 0
                ? WebhookAttemptPreparer.AttemptType.INITIAL : WebhookAttemptPreparer.AttemptType.ORDINARY_RETRY;
        while (session.usable()) {
            ApplicationWebhook config = configurations.selectByApplicationId(event.getApplicationId());
            if (!session.usable()) return;
            if (config == null || !event.getApplicationId().equals(config.getApplicationId())) {
                finish(session, claim, new DeliveryResult(DeliveryStatus.DEAD, "CONFIG_NOT_FOUND", null)); return;
            }
            if (Integer.valueOf(1).equals(config.getStatus())) {
                finish(session, claim, new DeliveryResult(DeliveryStatus.RETRY, "CONFIG_DISABLED", Duration.ofMinutes(1))); return;
            }
            if (!Integer.valueOf(0).equals(config.getStatus())) {
                finish(session, claim, new DeliveryResult(DeliveryStatus.DEAD, "CONFIGURATION_ERROR", null)); return;
            }
            if (type == WebhookAttemptPreparer.AttemptType.ORDINARY_RETRY
                    && responses.retriesExhausted(snapshot.ordinaryRetriesUsed())) {
                finish(session, claim, new DeliveryResult(DeliveryStatus.DEAD, "HTTP_RETRIES_EXHAUSTED", null)); return;
            }
            List<WebhookSecret> current = secrets.selectCurrentSecretsByWebhookId(config.getId(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            if (!session.usable()) return;
            if (current.size() != 1 || !config.getId().equals(current.get(0).getWebhookId())) {
                finish(session, claim, new DeliveryResult(DeliveryStatus.DEAD, "CURRENT_SECRET_INVALID", null)); return;
            }
            WebhookSecret secret = current.get(0);
            byte[] raw;
            try { raw = crypto.decrypt(new EncryptedSecret(secret.getSecretCiphertext(), secret.getSecretNonce(), secret.getEncryptionAlgorithm(), secret.getEncryptionKeyVersion())); }
            catch (RuntimeException e) { failure(session, claim, snapshot, e, WebhookExceptionClassifier.Operation.SECRET_DECRYPTION); return; }
            long timestamp = clock.instant().getEpochSecond();
            String signature;
            try { signature = signatures.sign(raw, timestamp, payload); }
            finally { Arrays.fill(raw, (byte) 0); }
            if (!session.usable()) return;
            WebhookHttpClient.PreparedPost post;
            try { post = http.preparePost(config.getUrl(), payload, timestamp, signature); }
            catch (Exception e) { failure(session, claim, snapshot, e, WebhookExceptionClassifier.Operation.URL_VALIDATION); return; }
            if (!session.usable()) return;
            HttpCancellationBridge bridge = new HttpCancellationBridge(session.cancellation());
            FutureTask<Void> abort = new FutureTask<>(() -> null) {
                @Override public boolean cancel(boolean interrupt) { post.cancel(); return super.cancel(interrupt); }
            };
            bridge.bind(abort);
            if (!session.usable()) return;
            WebhookAttemptPreparer.AttemptResult<WebhookHttpResponse> result;
            try {
                var optional = preparer.prepareAndSend(id, token, snapshot, type, config.getUrl(), secret.getId(), committed -> {
                    if (!session.usable()) throw new java.util.concurrent.CancellationException();
                    return post.send();
                });
                if (optional.isEmpty()) return;
                result = optional.orElseThrow();
            } catch (WebhookSendException e) {
                failure(session, claim, e.snapshot(), e.getCause(), WebhookExceptionClassifier.Operation.DELIVERY); return;
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            if (!session.usable()) return;
            snapshot = result.snapshot();
            DeliveryResult decision = responses.classify(result.response(), Math.toIntExact(snapshot.ordinaryRetriesUsed()), snapshot.secretRefreshAttempted());
            if (decision.status() == DeliveryStatus.REFRESH_SECRET) {
                type = WebhookAttemptPreparer.AttemptType.SECRET_REFRESH; continue;
            }
            finish(session, claim, decision); return;
        }
    }

    private void failure(WebhookLeaseManager.Session session, ClaimedWebhookEvent claim, WebhookAttemptPreparer.Snapshot snapshot,
                         Throwable error, WebhookExceptionClassifier.Operation operation) {
        WebhookFailure failure = classifier.classify(error, operation, session.status());
        if (!session.usable()) return;
        if (failure.category() == FailureCategory.PERMANENT) {
            finish(session, claim, new DeliveryResult(DeliveryStatus.DEAD, failure.failureCode(), null));
        } else if (failure.category() == FailureCategory.RETRYABLE) {
            // Network attempts are finite; DNS failures happen before admission and have no separate persisted budget.
            int used = Math.toIntExact(snapshot.ordinaryRetriesUsed());
            DeliveryResult retry = operation == WebhookExceptionClassifier.Operation.DELIVERY
                    ? responses.retryableFailure(failure.failureCode(), used)
                    : new DeliveryResult(DeliveryStatus.RETRY, failure.failureCode(), responses.retryDelay(used));
            finish(session, claim, retry);
        } else if (failure.category() == FailureCategory.INFRASTRUCTURE) {
            log.error("Webhook infrastructure failure, eventId={}, failureCode={}, errorType={}",
                    claim.event().getEventId(), failure.failureCode(), error.getClass().getSimpleName());
        }
    }

    private void finish(WebhookLeaseManager.Session session, ClaimedWebhookEvent claim, DeliveryResult result) {
        long id = claim.event().getId(); String token = claim.claimToken();
        session.complete(() -> switch (result.status()) {
            case DELIVERED -> outbox.markDelivered(id, token);
            case DEAD -> outbox.markDead(id, token, result.failureCode());
            case RETRY -> outbox.scheduleRetry(id, token, Math.multiplyExact(result.retryDelay().toMillis(), 1000), result.failureCode());
            case REFRESH_SECRET -> throw new IllegalStateException("refresh is not final");
        });
    }

    @Override public void close() {
        WebhookLeaseManager.Session[] sessions;
        synchronized (this) { closed = true; sessions = active.toArray(WebhookLeaseManager.Session[]::new); }
        for (var session : sessions) session.close();
    }
}
