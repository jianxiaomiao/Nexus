package com.nexus.webhook.dispatch;

@FunctionalInterface
public interface WebhookWorker {
    void process(ClaimedWebhookEvent event);
}
