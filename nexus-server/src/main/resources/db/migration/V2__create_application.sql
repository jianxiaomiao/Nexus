CREATE TABLE applications
(
    id  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '唯一id标识',
    owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '用户id，外键关联',
    name VARCHAR(64) NOT NULL COMMENT 'application名字，同一用户不允许再重复',
    status TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0：启用，1：禁用',
    is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '软删除标识，0：正常，1：删除',
    deleted_at DATETIME(3) NULL COMMENT '删除时间戳',
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                             ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_apps_name (owner_user_id,name),
    CONSTRAINT chk_apps_status CHECK (status IN (0, 1)),
    CONSTRAINT chk_apps_is_deleted CHECK (is_deleted IN (0, 1)),
    CONSTRAINT chk_apps_deletion_state CHECK (
        (is_deleted = 0 AND deleted_at IS NULL)
            OR (is_deleted = 1 AND deleted_at IS NOT NULL)
        ),
    CONSTRAINT fk_applications_owner
        FOREIGN KEY (owner_user_id)
        REFERENCES users (id)
)ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Nexus management applications';