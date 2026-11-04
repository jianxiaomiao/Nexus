package com.nexus.webhook.dispatch;

import java.time.LocalDateTime;

/** Values read from the database in the same transaction as the lease update. */
public record LeaseSnapshot(LocalDateTime leaseExpiresAt, LocalDateTime databaseNow) {}
