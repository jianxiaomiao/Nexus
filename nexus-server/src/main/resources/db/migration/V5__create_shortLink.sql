CREATE TABLE short_links
(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    api_key_id BIGINT UNSIGNED NOT NULL COMMENT '所属应用id',
    name VARCHAR(64) NOT NULL COMMENT 'shortLinkName',
    original_url VARCHAR(2048) NOT NULL COMMENT '原始url链接，不允许修改',
    short_code VARCHAR(12) NOT NULL COMMENT '服务端生成的短code，不允许重复',
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0：启用，1：禁用',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '软删除标识，0：正常，1：删除',
    expires_at DATETIME(3) NOT NULL COMMENT '当前时间 >= expires_at 即到期，过期时间戳',
    deleted_at DATETIME(3) NULL COMMENT '删除时间戳',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                             ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_links_short_code ( short_code),
    CONSTRAINT chk_short_links_status CHECK (status IN (0, 1)),
    CONSTRAINT chk_short_links_is_deleted CHECK (is_deleted IN (0, 1)),
    CONSTRAINT chk_short_links_deletion_state CHECK (
        (is_deleted = 0 AND deleted_at IS NULL)
            OR (is_deleted = 1 AND deleted_at IS NOT NULL)
        ),
    CONSTRAINT fk_short_links_api_keys
        FOREIGN KEY (api_key_id)
            REFERENCES api_keys (id)
)ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Nexus API keys';
