package com.nexus.webhook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.webhook.entity.WebhookOutbox;
import com.nexus.webhook.dispatch.LeaseSnapshot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;

import java.time.LocalDateTime;
import java.util.List;

public interface WebhookOutboxMapper extends BaseMapper<WebhookOutbox> {
    @Insert("""
            INSERT INTO webhook_outbox (event_id, event_type, application_id, job_id, event_status,
                completed_at, result_size_bytes, result_sha256, result_expires_at, failure_code,
                query_url, status, attempt_count, secret_refresh_attempted, next_attempt_at)
            SELECT #{eventId}, 'screenshot.completed', k.application_id, j.job_id,
                CASE WHEN j.status = 2 THEN 0 ELSE 1 END, j.completed_at, j.result_size_bytes,
                j.result_sha256, j.result_expires_at, j.failure_code,
                CONCAT('/v1/web/screenshot-jobs/', j.job_id), 0, 0, 0, UTC_TIMESTAMP(3)
            FROM screenshot_jobs j JOIN api_keys k ON k.id = j.api_key_id
            JOIN application_webhooks w ON w.application_id = k.application_id
            WHERE j.id = #{jobId} AND j.status IN (2, 3)
            """)
    int insertScreenshotCompletion(@Param("jobId") long jobId, @Param("eventId") String eventId);
    @Select("SELECT lease_expires_at AS leaseExpiresAt, UTC_TIMESTAMP(6) AS databaseNow FROM webhook_outbox " +
            "WHERE id = #{id} AND status = 1 AND claim_token = #{token}")
    LeaseSnapshot selectLeaseSnapshot(@Param("id") long id, @Param("token") String token);
    // Requires lockForCompletion in the same transaction before this statement.
    @Update("UPDATE webhook_outbox SET lease_expires_at = TIMESTAMPADD(MICROSECOND, #{ttlMicros}, UTC_TIMESTAMP(6)) " +
            "WHERE id = #{id} AND status = 1 AND claim_token = #{token} AND lease_expires_at > UTC_TIMESTAMP(6)")
    int renewLease(@Param("id") long id, @Param("token") String token,
                   @Param("ttlMicros") long ttlMicros);

    // Call the two locking reads inside the transaction that performs their updates.
    @Select("SELECT * FROM webhook_outbox WHERE status = 0 AND next_attempt_at <= UTC_TIMESTAMP(6) " +
            "ORDER BY next_attempt_at, id LIMIT #{batchSize} FOR UPDATE SKIP LOCKED")
    List<WebhookOutbox> selectDueForUpdate(@Param("batchSize") int batchSize);

    @Update("UPDATE webhook_outbox SET status = 1, claim_token = #{token}, lease_expires_at = TIMESTAMPADD(MICROSECOND, #{ttlMicros}, UTC_TIMESTAMP(6)) " +
            "WHERE id = #{id} AND status = 0 AND next_attempt_at <= UTC_TIMESTAMP(6)")
    int claim(@Param("id") long id, @Param("token") String token, @Param("ttlMicros") long ttlMicros);

    @Update("UPDATE webhook_outbox SET status = 0, next_attempt_at = UTC_TIMESTAMP(6), claim_token = NULL, lease_expires_at = NULL " +
            "WHERE id = #{id} AND status = 1 AND claim_token = #{token}")
    int releaseClaim(@Param("id") long id, @Param("token") String token);

    // Hold the row lock in the same transaction before any final update, so UTC_TIMESTAMP
    // is evaluated by a new statement after lock acquisition.
    @Select("SELECT id FROM webhook_outbox WHERE id = #{id} FOR UPDATE")
    Long lockForCompletion(@Param("id") long id);

    // Acquire lockForCompletion first, so database time is evaluated after the row lock.
    @Update("UPDATE webhook_outbox SET attempt_count = attempt_count + 1, secret_refresh_attempted = #{newRefresh} " +
            "WHERE id = #{id} AND status = 1 AND claim_token = #{token} AND lease_expires_at > UTC_TIMESTAMP(6) " +
            "AND attempt_count = #{expectedCount} AND secret_refresh_attempted = #{expectedRefresh}")
    int prepareAttempt(@Param("id") long id, @Param("token") String token,
                       @Param("expectedCount") long expectedCount, @Param("expectedRefresh") boolean expectedRefresh,
                       @Param("newRefresh") boolean newRefresh);

    @Select("SELECT * FROM webhook_outbox WHERE id = #{id} AND status = 1 AND claim_token = #{token} AND lease_expires_at > UTC_TIMESTAMP(6)")
    WebhookOutbox selectClaimed(@Param("id") long id, @Param("token") String token);

    @Update("UPDATE webhook_outbox SET delivery_url = #{url}, webhook_secret_id = #{secretId} " +
            "WHERE id = #{id} AND status = 1 AND claim_token = #{token} AND lease_expires_at > UTC_TIMESTAMP(6)")
    int recordDeliveryConfig(@Param("id") long id, @Param("token") String token,
                             @Param("url") String url, @Param("secretId") long secretId);

    // All three final updates require lockForCompletion in the same transaction first.
    @Update("UPDATE webhook_outbox SET status = 2, delivered_at = UTC_TIMESTAMP(6), claim_token = NULL, " +
            "lease_expires_at = NULL, last_failure_code = NULL WHERE id = #{id} AND status = 1 AND claim_token = #{token} AND lease_expires_at > UTC_TIMESTAMP(6)")
    int markDelivered(@Param("id") long id, @Param("token") String token);

    @Update("UPDATE webhook_outbox SET status = 0, next_attempt_at = TIMESTAMPADD(MICROSECOND, #{delayMicros}, UTC_TIMESTAMP(6)), last_failure_code = #{failureCode}, " +
            "claim_token = NULL, lease_expires_at = NULL WHERE id = #{id} AND status = 1 AND claim_token = #{token} AND lease_expires_at > UTC_TIMESTAMP(6)")
    int scheduleRetry(@Param("id") long id, @Param("token") String token,
                      @Param("delayMicros") long delayMicros, @Param("failureCode") String failureCode);

    @Update("UPDATE webhook_outbox SET status = 3, last_failure_code = #{failureCode}, claim_token = NULL, " +
            "lease_expires_at = NULL WHERE id = #{id} AND status = 1 AND claim_token = #{token} AND lease_expires_at > UTC_TIMESTAMP(6)")
    int markDead(@Param("id") long id, @Param("token") String token, @Param("failureCode") String failureCode);

    @Select("SELECT * FROM webhook_outbox WHERE status = 1 AND lease_expires_at <= UTC_TIMESTAMP(6) " +
            "ORDER BY lease_expires_at, id LIMIT #{batchSize} FOR UPDATE SKIP LOCKED")
    List<WebhookOutbox> selectExpiredForUpdate(@Param("batchSize") int batchSize);

    @Update("UPDATE webhook_outbox SET status = 0, next_attempt_at = UTC_TIMESTAMP(6), claim_token = NULL, lease_expires_at = NULL " +
            "WHERE id = #{id} AND status = 1 AND claim_token = #{token} AND lease_expires_at <= UTC_TIMESTAMP(6)")
    int recoverExpired(@Param("id") long id, @Param("token") String token);
}
