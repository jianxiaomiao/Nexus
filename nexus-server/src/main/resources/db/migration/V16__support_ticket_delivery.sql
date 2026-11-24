CREATE TABLE support_ticket (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    submission_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submission_digest BINARY(32) NOT NULL,
    application_id BIGINT UNSIGNED NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    UNIQUE KEY uk_support_submission (owner_user_id, submission_key),
    KEY ix_support_owner_time (owner_user_id, created_at, id)
) ENGINE=InnoDB;

CREATE TABLE support_ticket_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    integration_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_expires_at DATETIME(3) NULL,
    last_failure VARCHAR(40) NULL,
    flowops_ticket_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
    flowops_ticket_no VARCHAR(40) NULL,
    delivered_at DATETIME(3) NULL,
    UNIQUE KEY uk_support_ticket_event (ticket_id),
    KEY ix_support_due (integration_id, status, next_attempt_at, event_id),
    KEY ix_support_lease (integration_id, status, lease_expires_at),
    CONSTRAINT fk_support_ticket_event FOREIGN KEY (ticket_id) REFERENCES support_ticket (id)
) ENGINE=InnoDB;
