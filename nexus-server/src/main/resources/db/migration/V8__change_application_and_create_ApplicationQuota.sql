ALTER TABLE applications
    ADD COLUMN quota INT NOT NULL DEFAULT 1000
    COMMENT '应用每个720小时周期的配额上限',
    ADD CONSTRAINT chk_applications_quota
        CHECK (quota >= 0);

CREATE TABLE application_quotas
(
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键',
    application_id BIGINT UNSIGNED NOT NULL COMMENT '所属应用Id',
    period_start DATETIME(3) NOT NULL COMMENT '720小时周期起始时间',
    quota_limit INT NOT NULL COMMENT '创建周期记录时保存的应用配额上限快照',
    used_count INT NOT NULL DEFAULT 0 COMMENT '该周期消耗次数，默认0',
    PRIMARY KEY (id),
    CONSTRAINT fk_application_quotas_applications
        FOREIGN KEY (application_id)
            REFERENCES applications (id),
    CONSTRAINT chk_application_quotas_limit
        CHECK (quota_limit >= 0),
    CONSTRAINT chk_application_quotas_used
        CHECK (used_count >= 0 AND used_count <= quota_limit),
    UNIQUE KEY uk_application_period(application_id, period_start)
)ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Nexus Application Quotas';
