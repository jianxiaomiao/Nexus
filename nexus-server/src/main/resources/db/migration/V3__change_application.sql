ALTER TABLE applications
    ADD COLUMN active_slot TINYINT
        GENERATED ALWAYS AS (CASE WHEN is_deleted = 0 THEN 1 ELSE NULL END) VIRTUAL;

ALTER TABLE applications
    ADD UNIQUE KEY uk_apps_active_name (owner_user_id, name, active_slot);

ALTER TABLE applications
DROP INDEX uk_apps_name;