# Webhook attempt admissions

## Management boundary

Management endpoints under `/api/application/{applicationId}/webhook` use Bearer JWT and verify Application ownership. A configuration and its first encrypted secret commit in one transaction; the unique Application constraint resolves concurrent creation. Secret rotation locks the configuration, compares the caller's `currentSecretVersion`, moves the old secret to one-hour grace, and inserts the new CURRENT secret in the same transaction. A stale version returns a conflict rather than silently replacing a secret another caller just received. Endpoint DNS is preflighted when saved and checked again by the delivery connection.

`attempt_count` counts database-committed admissions: the initial attempt, ordinary retries, and the optional 401 secret-refresh attempt. `secret_refresh_attempted` means the refresh admission was committed, not that its network request completed. For a nonzero count, ordinary retries used are `attempt_count - 1 - (secret_refresh_attempted ? 1 : 0)`. A crash between commit and HTTP can consume an admission without starting a request.

- A successful send returns the committed snapshot with the response.
- A send failure throws `WebhookSendException` with the committed snapshot and original cause; subsequent decisions must use that snapshot.
- A preparation or commit failure propagates unchanged and does not start HTTP. The commit outcome may be unknown, so the caller must not assume a new snapshot or blindly retry from the old one.

## Delivery request and preflight

The request body is serialized once per claimed event. Each HTTP admission signs the exact bytes sent using HMAC-SHA256 over the ASCII Unix timestamp in seconds, a dot, and the body. The request carries the decimal timestamp in `X-Nexus-Timestamp` and the lowercase hexadecimal signature in `X-Nexus-Signature`; the HMAC key is the decrypted raw secret. An HTTP 200 is success under the current response policy.

The current endpoint and current secret are read again for each admission, including the one allowed 401 refresh. The chosen URL and secret ID are recorded in the same transaction as the admission. A disabled configuration pauses the event as PENDING without consuming an HTTP admission; missing configuration or an invalid current secret is a permanent failure. Transient DNS failures happen before admission and are rescheduled with backoff. They do not consume an HTTP attempt and currently have no independent finite retry limit.

## Screenshot completion and scheduling

Each new screenshot terminal transition (success, known failure, or exhausted worker lease) commits with its `screenshot.completed` outbox event in the same database transaction. Both outcomes use the same event type; `event_status` and the result or failure fields distinguish them. The unique `(job_id, event_type)` constraint prevents duplicate events. A webhook configuration must exist at completion time; disabled configurations still receive a pending event. Existing terminal jobs are not backfilled when this linkage is enabled or a configuration is added later. The stored `query_url` is the relative `/v1/web/screenshot-jobs/{jobId}` path; it is not currently included in the delivery payload.

The application starts dispatch and expired-lease recovery automatically, with independent fixed-delay tasks. Settings are `nexus.webhook.scheduling.enabled` (default `true`), `dispatch-delay` (default `1s`), `recovery-delay` (default `5s`), and `recovery-batch-size` (default `100`, valid 1–1000). Disabling scheduling does not disable creation of outbox events. Recovery locks a bounded batch and uses database UTC time; it returns expired claims to pending without erasing committed attempt admissions. Failures are logged and the next scheduled run continues.

Shutdown stops scheduling, waits up to ten seconds for scheduling calls, cancels active worker sessions, and waits up to ten seconds for the worker executor. A database call that ignores interruption may outlive that wait; any committed but unfinished claim is recovered after its lease expires. A stopped scheduler is not restartable within the same application context; restart the application to start it again.

Delivery is **at least once**: a receiver may process a request even when its response is lost, so Nexus can send the same event again. The `eventId` is stable across ordinary retries, lease recovery, and the optional 401 secret-refresh admission. Receivers should deduplicate using a persistent Inbox or unique constraint on `eventId`; the sender cannot guarantee exactly-once processing. `event_status` is stored internally and is not currently sent as a payload field; consumers distinguish the outcome from `result` and `failureCode`.
