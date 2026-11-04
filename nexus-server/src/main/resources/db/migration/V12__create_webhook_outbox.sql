CREATE TABLE webhook_outbox
(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件 UUID',
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件类型',
    application_id BIGINT UNSIGNED NOT NULL COMMENT '所属 Application ID',
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对外公开的任务 UUID',
    event_status TINYINT UNSIGNED NOT NULL COMMENT '0：成功，1：失败',
    completed_at DATETIME(3) NOT NULL COMMENT '任务完成时间（UTC）',
    result_size_bytes BIGINT UNSIGNED NULL COMMENT '结果字节数',
    result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '结果 SHA-256',
    result_expires_at DATETIME(3) NULL COMMENT '结果过期时间（UTC）',
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '任务失败码',
    query_url VARCHAR(2048) NOT NULL COMMENT '任务查询地址',
    delivery_url VARCHAR(2048) NULL COMMENT '通知投递地址',
    webhook_secret_id BIGINT UNSIGNED NULL COMMENT '投递使用的 Secret ID',
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0：待投递，1：处理中，2：已投递，3：终止',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
    next_attempt_at DATETIME(3) NOT NULL COMMENT '下次投递时间（UTC），插入时必须显式提供',
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '投递认领令牌',
    lease_expires_at DATETIME(3) NULL COMMENT '认领租约到期时间（UTC）',
    delivered_at DATETIME(3) NULL COMMENT '投递成功时间（UTC）',
    last_failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近一次投递失败码',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间（UTC）',

    PRIMARY KEY (id),
    UNIQUE KEY uk_webhook_outbox_event_id (event_id),
    UNIQUE KEY uk_webhook_outbox_job_event (job_id, event_type),
    INDEX idx_webhook_outbox_status_next (status, next_attempt_at, id),
    INDEX idx_webhook_outbox_status_lease (status, lease_expires_at, id),
    INDEX idx_webhook_outbox_application_created (application_id, created_at),
    CONSTRAINT fk_webhook_outbox_application FOREIGN KEY (application_id) REFERENCES applications (id),
    CONSTRAINT fk_webhook_outbox_secret FOREIGN KEY (webhook_secret_id) REFERENCES webhook_secrets (id),
    CONSTRAINT chk_webhook_outbox_event_status CHECK (event_status IN (0, 1)),
    CONSTRAINT chk_webhook_outbox_status CHECK (status IN (0, 1, 2, 3))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Webhook 事件发件箱';
