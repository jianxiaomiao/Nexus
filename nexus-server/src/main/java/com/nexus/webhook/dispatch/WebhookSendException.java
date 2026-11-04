package com.nexus.webhook.dispatch;

import java.util.Objects;

/** A send failed after admission committed. The cause retains the original failure type. */
public final class WebhookSendException extends RuntimeException {
    private final WebhookAttemptPreparer.Snapshot snapshot;

    public WebhookSendException(WebhookAttemptPreparer.Snapshot snapshot, Throwable cause) {
        super("Webhook send failed after admission", Objects.requireNonNull(cause));
        this.snapshot = Objects.requireNonNull(snapshot);
    }

    public WebhookAttemptPreparer.Snapshot snapshot() { return snapshot; }
}
