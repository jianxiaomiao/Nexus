-- Nullable for previously shipped clients. A supplied key is scoped to the immutable API Key ID.
-- Timestamp-prefixed UUID keys expire after seven days; expired keys are never accepted as new submissions.
ALTER TABLE screenshot_jobs
    ADD COLUMN submission_key VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN submission_expires_at DATETIME(3) NULL,
    ADD UNIQUE KEY uk_screenshot_submission (api_key_id, submission_key);
