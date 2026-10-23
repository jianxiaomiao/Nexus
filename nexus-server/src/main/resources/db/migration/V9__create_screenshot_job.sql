CREATE TABLE screenshot_jobs
(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '对外公开的随机任务 UUID',
    api_key_id BIGINT UNSIGNED NOT NULL COMMENT '创建任务的 API Key ID',
    target_url VARCHAR(2048) NOT NULL COMMENT '通过同步策略校验后保存的截图目标 URL',

    status TINYINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '0：排队中，1：执行中，2：成功，3：失败，4：结果已过期',
    attempt_count TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已经开始的执行次数',
    run_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '当前执行认领令牌，只有持有同一令牌的 Worker 可以提交结果',
    lease_expires_at DATETIME(3) NULL COMMENT '当前执行租约到期时间（UTC）',

    result_size_bytes BIGINT UNSIGNED NULL COMMENT '成功 PNG 的字节数',
    result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '成功 PNG 的 SHA-256',
    blocked_resources INT UNSIGNED NULL COMMENT '截图时被安全策略阻止的资源数量',
    resource_limit_reached TINYINT UNSIGNED NULL COMMENT '是否触发资源请求次数上限',
    result_expires_at DATETIME(3) NULL COMMENT '成功结果停止下载并转为过期的时间（UTC）',
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '稳定失败码，不保存异常堆栈',

    started_at DATETIME(3) NULL COMMENT '任务第一次开始执行的时间（UTC）',
    completed_at DATETIME(3) NULL COMMENT '任务进入成功或失败终态的时间（UTC）',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '任务创建时间（UTC）',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间（UTC）',

    PRIMARY KEY (id),
    UNIQUE KEY uk_screenshot_jobs_job_id (job_id),
    INDEX idx_screenshot_jobs_status_created (status, created_at, id),
    INDEX idx_screenshot_jobs_status_lease (status, lease_expires_at),
    INDEX idx_screenshot_jobs_status_result_expiry (status, result_expires_at),
    INDEX idx_screenshot_jobs_status_completed (status, completed_at),

    CONSTRAINT fk_screenshot_jobs_api_keys
        FOREIGN KEY (api_key_id)
            REFERENCES api_keys (id),
    CONSTRAINT chk_screenshot_jobs_target_url
        CHECK (CHAR_LENGTH(target_url) > 0),
    CONSTRAINT chk_screenshot_jobs_status
        CHECK (status IN (0, 1, 2, 3, 4)),
    CONSTRAINT chk_screenshot_jobs_resource_limit
        CHECK (resource_limit_reached IS NULL OR resource_limit_reached IN (0, 1)),
    CONSTRAINT chk_screenshot_jobs_completed_time
        CHECK (completed_at IS NULL OR (started_at IS NOT NULL AND completed_at >= started_at)),
    CONSTRAINT chk_screenshot_jobs_result_expiry
        CHECK (result_expires_at IS NULL OR (completed_at IS NOT NULL AND result_expires_at > completed_at)),
    CONSTRAINT chk_screenshot_jobs_state
        CHECK (
            (
                status = 0
                AND run_token IS NULL
                AND lease_expires_at IS NULL
                AND completed_at IS NULL
                AND result_size_bytes IS NULL
                AND result_sha256 IS NULL
                AND blocked_resources IS NULL
                AND resource_limit_reached IS NULL
                AND result_expires_at IS NULL
                AND failure_code IS NULL
            )
            OR
            (
                status = 1
                AND attempt_count >= 1
                AND run_token IS NOT NULL
                AND lease_expires_at IS NOT NULL
                AND lease_expires_at > started_at
                AND started_at IS NOT NULL
                AND completed_at IS NULL
                AND result_size_bytes IS NULL
                AND result_sha256 IS NULL
                AND blocked_resources IS NULL
                AND resource_limit_reached IS NULL
                AND result_expires_at IS NULL
                AND failure_code IS NULL
            )
            OR
            (
                status IN (2, 4)
                AND attempt_count >= 1
                AND run_token IS NULL
                AND lease_expires_at IS NULL
                AND started_at IS NOT NULL
                AND completed_at IS NOT NULL
                AND result_size_bytes IS NOT NULL
                AND result_size_bytes > 0
                AND result_sha256 IS NOT NULL
                AND blocked_resources IS NOT NULL
                AND resource_limit_reached IS NOT NULL
                AND result_expires_at IS NOT NULL
                AND failure_code IS NULL
            )
            OR
            (
                status = 3
                AND attempt_count >= 1
                AND run_token IS NULL
                AND lease_expires_at IS NULL
                AND started_at IS NOT NULL
                AND completed_at IS NOT NULL
                AND result_size_bytes IS NULL
                AND result_sha256 IS NULL
                AND blocked_resources IS NULL
                AND resource_limit_reached IS NULL
                AND result_expires_at IS NULL
                AND failure_code IS NOT NULL
                AND CHAR_LENGTH(failure_code) > 0
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '异步静态网页截图任务';
