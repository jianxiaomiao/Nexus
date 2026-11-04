ALTER TABLE webhook_outbox
    ADD COLUMN secret_refresh_attempted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'One-time secret refresh admitted',
    ADD CONSTRAINT chk_webhook_outbox_secret_refresh_attempted CHECK (secret_refresh_attempted IN (0, 1)),
    ADD CONSTRAINT chk_webhook_outbox_refresh_attempt_count CHECK (secret_refresh_attempted = 0 OR attempt_count >= 2);
