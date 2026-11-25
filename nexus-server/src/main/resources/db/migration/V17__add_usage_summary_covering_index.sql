ALTER TABLE usage_events
    ADD INDEX idx_usage_summary_cover (api_key_id, application_id, occurred_at, api_code),
    ALGORITHM=INPLACE,
    LOCK=NONE;
