CREATE TABLE email_verification_codes
(
    id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT 'Verification code record id',
    email         VARCHAR(254)     NOT NULL COMMENT 'Normalized target email',
    purpose       VARCHAR(32)      NOT NULL COMMENT 'Verification purpose, e.g. REGISTER',
    code_hash     CHAR(64)         NOT NULL COMMENT 'SHA-256 hex digest of verification code',
    expires_at    DATETIME(3)      NOT NULL COMMENT 'Expiration time (UTC)',
    attempt_count TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Failed verification attempts',
    consumed_at   DATETIME(3)      NULL COMMENT 'Time when code was consumed',
    created_at    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Creation time (UTC)',

    PRIMARY KEY (id),
    INDEX idx_evc_email_purpose_created (email, purpose, created_at DESC)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Email verification codes';
