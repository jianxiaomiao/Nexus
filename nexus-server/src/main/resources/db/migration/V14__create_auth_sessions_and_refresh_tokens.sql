CREATE TABLE auth_sessions
(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '登录会话主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属用户',
    created_at DATETIME(3) NOT NULL COMMENT '创建时间（UTC）',
    expires_at DATETIME(3) NOT NULL COMMENT '固定绝对到期时间（UTC）',
    revoked_at DATETIME(3) NULL DEFAULT NULL COMMENT '首次撤销时间（UTC）',
    PRIMARY KEY (id),
    INDEX idx_auth_sessions_user_id (user_id),
    INDEX idx_auth_sessions_expires_at (expires_at),
    INDEX idx_auth_sessions_revoked_at (revoked_at),
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_auth_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_auth_sessions_revocation CHECK (revoked_at IS NULL OR revoked_at >= created_at)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '管理登录会话';

CREATE TABLE refresh_tokens
(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '本代刷新凭据主键',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属登录会话',
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'RT文本SHA-256小写hex摘要',
    created_at DATETIME(3) NOT NULL COMMENT '签发时间（UTC）',
    consumed_at DATETIME(3) NULL DEFAULT NULL COMMENT '首次消费时间（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_token_hash (token_hash),
    INDEX idx_refresh_tokens_session_id (session_id),
    CONSTRAINT fk_refresh_tokens_session FOREIGN KEY (session_id) REFERENCES auth_sessions (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_refresh_tokens_hash_length CHECK (CHAR_LENGTH(token_hash) = 64),
    CONSTRAINT chk_refresh_tokens_consumption CHECK (consumed_at IS NULL OR consumed_at >= created_at)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '刷新凭据历史摘要';
