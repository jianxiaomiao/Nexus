CREATE TABLE users
(
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Internal database identifier',
    email             VARCHAR(254)    NOT NULL COMMENT 'Normalized email used for login',
    display_name      VARCHAR(64)     NOT NULL COMMENT 'User-facing display name',
    password_hash     VARCHAR(255)    NOT NULL COMMENT 'One-way password hash',
    email_verified_at DATETIME(3)     NULL COMMENT 'Time at which the email was verified',
    status            TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Account status: 0=ACTIVE, 1=BANNED',
    is_deleted        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Soft deletion flag: 0=ACTIVE, 1=DELETED',
    deleted_at        DATETIME(3)     NULL COMMENT 'Time at which account deletion was requested',
    created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Creation time',
    updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                             ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last update time',

    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email),
    CONSTRAINT chk_users_status CHECK (status IN (0, 1)),
    CONSTRAINT chk_users_is_deleted CHECK (is_deleted IN (0, 1)),
    CONSTRAINT chk_users_deletion_state CHECK (
        (is_deleted = 0 AND deleted_at IS NULL)
        OR (is_deleted = 1 AND deleted_at IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Nexus management users';
