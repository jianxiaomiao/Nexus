package com.nexus.webhook.dispatch;

import com.nexus.webhook.entity.WebhookOutbox;
import java.time.LocalDateTime;

public record ClaimedWebhookEvent(WebhookOutbox event, String claimToken, LocalDateTime leaseExpiresAt,
                                  long budgetStartedNanos, long budgetNanos) {}
