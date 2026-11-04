CREATE TABLE application_webhooks
(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    application_id BIGINT UNSIGNED NOT NULL COMMENT '所属 Application ID',
    url VARCHAR(2048) NOT NULL COMMENT '客户端接收通知的地址',
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0：启用，1：禁用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间（UTC）',

    PRIMARY KEY (id),
    UNIQUE KEY uk_application_webhooks_application_id (application_id),
    CONSTRAINT fk_application_webhooks_application
        FOREIGN KEY (application_id) REFERENCES applications (id),
    CONSTRAINT chk_application_webhooks_url CHECK (CHAR_LENGTH(url) > 0),
    CONSTRAINT chk_application_webhooks_status CHECK (status IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Application Webhook 配置';

CREATE TABLE webhook_secrets
(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    webhook_id BIGINT UNSIGNED NOT NULL COMMENT '所属 Webhook ID',
    secret_ciphertext VARBINARY(512) NOT NULL COMMENT 'AES-GCM 密文及认证标签',
    secret_nonce BINARY(12) NOT NULL COMMENT 'AES-GCM nonce',
    encryption_algorithm VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '加密算法名称',
    encryption_key_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '加密主密钥版本',
    secret_version INT UNSIGNED NOT NULL COMMENT 'Webhook Secret 版本',
    status TINYINT UNSIGNED NOT NULL COMMENT '0：CURRENT，1：GRACE，2：DISABLED',
    expires_at DATETIME(3) NULL COMMENT 'Secret 过期时间（UTC）',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间（UTC）',

    PRIMARY KEY (id),
    UNIQUE KEY uk_webhook_secrets_version (webhook_id, secret_version),
    CONSTRAINT fk_webhook_secrets_webhook
        FOREIGN KEY (webhook_id) REFERENCES application_webhooks (id),
    CONSTRAINT chk_webhook_secrets_status CHECK (status IN (0, 1, 2))
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Webhook Secret 版本';
