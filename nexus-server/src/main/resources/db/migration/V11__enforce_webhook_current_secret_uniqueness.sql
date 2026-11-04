ALTER TABLE webhook_secrets
    ADD COLUMN current_webhook_id BIGINT UNSIGNED
        GENERATED ALWAYS AS (CASE WHEN status = 0 THEN webhook_id ELSE NULL END) VIRTUAL,
    ADD UNIQUE KEY uk_webhook_secrets_current (current_webhook_id);
