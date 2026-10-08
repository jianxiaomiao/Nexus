CREATE TABLE usage_events
(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    api_key_id BIGINT UNSIGNED NOT NULL COMMENT '所属apiKeyId',
    application_id BIGINT UNSIGNED NOT NULL COMMENT '所属应用Id',
    api_code VARCHAR(24) NOT NULL COMMENT '稳定业务code',
    http_status_code SMALLINT UNSIGNED NOT NULL COMMENT 'HTTP 状态码',
    duration_ms INT NOT NULL COMMENT '耗时，单位为毫秒',
    occurred_at DATETIME(3) NOT NULL COMMENT '请求发起时间',
    PRIMARY KEY (id),
    -- 用于每日统计查询的联合索引（api_key_id + 时间，满足按key+时间范围统计）
    INDEX idx_api_time (api_key_id, occurred_at),
    CONSTRAINT fk_usage_events_api_keys
        FOREIGN KEY (api_key_id)
            REFERENCES api_keys (id),
    CONSTRAINT fk_usage_events_applications
        FOREIGN KEY (application_id)
            REFERENCES applications (id)

)ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Nexus Usage Events';
