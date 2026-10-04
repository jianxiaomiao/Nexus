-- 完整凭证格式：nxk_v1_<public_id>_<secret>。
-- public_id 为随机 UUID v4；secret 独立生成 32 字节随机值，以无填充 Base64URL 编码。
-- 只在创建响应中返回完整凭证；secret_hash 保存 secret 的 SHA-256 小写十六进制摘要。
-- key_preview 保存 'nxk_' + secret 前 6 字符 + '*****' + 末 4 字符；星号固定，不代表隐藏长度。
CREATE TABLE api_keys
(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    application_id BIGINT UNSIGNED NOT NULL COMMENT '所属应用id',
    name VARCHAR(64) NOT NULL COMMENT '同一应用内未删除的Key名称唯一',
    public_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '对外公开的随机UUID，用于定位Key',
    secret_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '独立随机Secret的SHA-256小写十六进制摘要',
    key_preview VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定遮蔽展示值，不参与认证，也不反映Secret长度',
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0：启用，1：禁用',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '软删除标识，0：正常，1：删除',
    deleted_at DATETIME(3) NULL COMMENT '删除时间戳',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                             ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last update time',
    active_slot TINYINT
        GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN 1 ELSE NULL END) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_keys_active_name (application_id, name, active_slot),
    UNIQUE KEY uk_api_keys_public_id (public_id),
    UNIQUE KEY uk_api_keys_secret_hash (secret_hash),
    CONSTRAINT chk_api_keys_status CHECK (status IN (0, 1)),
    CONSTRAINT chk_api_keys_is_deleted CHECK (is_deleted IN (0, 1)),
    CONSTRAINT chk_api_keys_deletion_state CHECK (
        (is_deleted = 0 AND deleted_at IS NULL)
            OR (is_deleted = 1 AND deleted_at IS NOT NULL)
        ),
    CONSTRAINT fk_api_keys_application
        FOREIGN KEY (application_id)
            REFERENCES applications (id)
)ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Nexus API keys';
