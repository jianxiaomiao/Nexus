ALTER TABLE usage_events
    ADD COLUMN event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '每次调用的事件 UUID';

UPDATE usage_events
SET event_id = UUID()
WHERE event_id IS NULL;

ALTER TABLE usage_events
    MODIFY COLUMN event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD UNIQUE KEY uk_usage_events_event_id (event_id);