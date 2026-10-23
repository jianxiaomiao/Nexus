package com.nexus.job.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.job.entity.ScreenshotJob;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ScreenshotJobMapper extends BaseMapper<ScreenshotJob> {
    @Select("""
            SELECT COUNT(*)
            FROM screenshot_jobs
            WHERE status IN (0, 1)
            """)
    long countUnfinished();

    @Select("""
            SELECT *
            FROM screenshot_jobs
            WHERE status = 0
            ORDER BY created_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    ScreenshotJob selectNextQueuedForUpdate();

    @Update("""
            UPDATE screenshot_jobs
            SET status = 1,
                attempt_count = attempt_count + 1,
                run_token = #{runToken},
                lease_expires_at = #{leaseExpiresAt},
                started_at = COALESCE(started_at, #{startedAt})
            WHERE id = #{id}
              AND status = 0
            """)
    int markRunningIfQueued(@Param("id") long id,
                            @Param("runToken") String runToken,
                            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
                            @Param("startedAt") LocalDateTime startedAt);

    @Update("""
            UPDATE screenshot_jobs
            SET status = 2,
                run_token = NULL,
                lease_expires_at = NULL,
                result_size_bytes = #{resultSizeBytes},
                result_sha256 = #{resultSha256},
                blocked_resources = #{blockedResources},
                resource_limit_reached = #{resourceLimitReached},
                result_expires_at = #{resultExpiresAt},
                completed_at = #{completedAt}
            WHERE id = #{id}
              AND status = 1
              AND run_token = #{runToken}
            """)
    int completeSucceededIfRunTokenMatches(
            @Param("id") long id,
            @Param("runToken") String runToken,
            @Param("resultSizeBytes") long resultSizeBytes,
            @Param("resultSha256") String resultSha256,
            @Param("blockedResources") int blockedResources,
            @Param("resourceLimitReached") boolean resourceLimitReached,
            @Param("resultExpiresAt") LocalDateTime resultExpiresAt,
            @Param("completedAt") LocalDateTime completedAt);

    @Update("""
            UPDATE screenshot_jobs
            SET status = 3,
                run_token = NULL,
                lease_expires_at = NULL,
                failure_code = #{failureCode},
                completed_at = #{completedAt}
            WHERE id = #{id}
              AND status = 1
              AND run_token = #{runToken}
            """)
    int markFailedIfRunTokenMatches(@Param("id") long id,
                                    @Param("runToken") String runToken,
                                    @Param("failureCode") String failureCode,
                                    @Param("completedAt") LocalDateTime completedAt);

    @Update("""
            UPDATE screenshot_jobs
            SET status = 0,
                run_token = NULL,
                lease_expires_at = NULL
            WHERE status = 1
              AND lease_expires_at <= #{now}
              AND attempt_count < #{maxAttempts}
            """)
    int requeueExpiredLeases(@Param("now") LocalDateTime now,
                             @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE screenshot_jobs
            SET status = 3,
                run_token = NULL,
                lease_expires_at = NULL,
                failure_code = 'WORKER_INTERRUPTED',
                completed_at = #{now}
            WHERE status = 1
              AND lease_expires_at <= #{now}
              AND attempt_count >= #{maxAttempts}
            """)
    int failExhaustedExpiredLeases(@Param("now") LocalDateTime now,
                                   @Param("maxAttempts") int maxAttempts);

    @Select("""
            SELECT *
            FROM screenshot_jobs
            WHERE status = 2
              AND result_expires_at <= #{now}
            ORDER BY result_expires_at, id
            LIMIT #{limit}
            """)
    List<ScreenshotJob> selectResultExpiryCandidates(@Param("now") LocalDateTime now,
                                                       @Param("limit") int limit);

    @Update("""
            UPDATE screenshot_jobs
            SET status = 4
            WHERE id = #{id}
              AND status = 2
              AND result_expires_at <= #{now}
            """)
    int markResultExpiredIfSucceeded(@Param("id") long id,
                                     @Param("now") LocalDateTime now);

    @Select("""
            SELECT *
            FROM screenshot_jobs
            WHERE status IN (3, 4)
              AND completed_at <= #{completedBefore}
            ORDER BY completed_at, id
            LIMIT #{limit}
            """)
    List<ScreenshotJob> selectMetadataCleanupCandidates(
            @Param("completedBefore") LocalDateTime completedBefore,
            @Param("limit") int limit);

    @Delete("""
            DELETE FROM screenshot_jobs
            WHERE id = #{id}
              AND status IN (3, 4)
              AND completed_at <= #{completedBefore}
            """)
    int deleteTerminalIfEligible(@Param("id") long id,
                                 @Param("completedBefore") LocalDateTime completedBefore);
}
