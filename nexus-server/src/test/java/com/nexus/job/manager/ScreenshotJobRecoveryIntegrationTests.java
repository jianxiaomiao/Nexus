package com.nexus.job.manager;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.credential.GeneratedApiKey;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.mapper.ScreenshotJobMapper;
import com.nexus.job.metrics.ScreenshotJobMetrics;
import com.nexus.job.store.ScreenshotResultStore;
import com.nexus.quota.QuotaTestCleanup;
import com.nexus.quota.entity.ApplicationQuota;
import com.nexus.quota.mapper.ApplicationQuotaMapper;
import com.nexus.quota.service.ApplicationQuotaService;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ScreenshotJobRecoveryIntegrationTests {
    private static final String URL = "https://blog.csdn.net/article";
    private static final Path RESULT_DIRECTORY = Path.of(
            "target", "test-data", "screenshot-job-recovery-results").toAbsolutePath();

    @DynamicPropertySource
    static void screenshotResultDirectory(DynamicPropertyRegistry registry) {
        registry.add("nexus.screenshot-jobs.result-directory", RESULT_DIRECTORY::toString);
    }

    /** 防止真实周期线程与本测试主动执行的状态转换竞争。 */
    @MockitoBean
    ScreenshotJobScheduler scheduler;

    @Autowired ScreenshotJobManager manager;
    @Autowired ScreenshotJobMapper jobs;
    @Autowired ScreenshotResultStore results;
    @Autowired ApplicationQuotaService quotaService;
    @Autowired ApplicationQuotaMapper quotas;
    @Autowired UserMapper users;
    @Autowired ApplicationMapper applications;
    @Autowired ApiKeyMapper keys;
    @Autowired ApiKeyCredentialGenerator credentials;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meterRegistry;

    private User owner;
    private Application application;
    private ApiKey key;

    @BeforeEach
    void fixtures() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)
                .startsWith("nexus_test_user@"));

        owner = new User();
        owner.setEmail("screenshot-recovery-" + UUID.randomUUID() + "@example.test");
        owner.setDisplayName("Screenshot recovery integration");
        owner.setPasswordHash("test-only-placeholder");
        users.insert(owner);

        application = new Application();
        application.setOwnerUserId(owner.getId());
        application.setName("screenshot-recovery-" + UUID.randomUUID());
        applications.insert(application);

        GeneratedApiKey generated = credentials.generate();
        key = new ApiKey();
        key.setApplicationId(application.getId());
        key.setName("recovery key");
        key.setPublicId(generated.publicId());
        key.setSecretHash(generated.secretHash());
        key.setKeyPreview(generated.keyPreview());
        keys.insert(key);
    }

    @AfterEach
    void cleanup() {
        if (key != null && key.getId() != null) {
            for (ScreenshotJob job : jobs.selectList(Wrappers.<ScreenshotJob>lambdaQuery()
                    .eq(ScreenshotJob::getApiKeyId, key.getId()))) {
                results.deleteIfExists(job.getJobId());
                jobs.deleteById(job.getId());
            }
            keys.deleteById(key.getId());
        }
        if (application != null && application.getId() != null) {
            QuotaTestCleanup.deletePeriods(quotas, application.getId());
            applications.deleteById(application.getId());
        }
        if (owner != null && owner.getId() != null) {
            users.deleteById(owner.getId());
        }
    }

    @AfterAll
    static void removeResultDirectory() throws Exception {
        if (!Files.exists(RESULT_DIRECTORY)) {
            return;
        }
        try (var paths = Files.walk(RESULT_DIRECTORY)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void unfinishedGaugeTracksPersistedQueuedAndRunningRows() {
        var gauge = meterRegistry.get(
                ScreenshotJobMetrics.UNFINISHED_JOBS_METRIC_NAME).gauge();
        double baseline = gauge.value();
        assertFalse(Double.isNaN(baseline));

        ScreenshotJob queued = queuedJob(UUID.randomUUID().toString());
        assertEquals(1, jobs.insert(queued));

        ScreenshotJob running = queuedJob(UUID.randomUUID().toString());
        LocalDateTime startedAt = utcNow();
        running.setStatus(1);
        running.setAttemptCount(1);
        running.setRunToken(UUID.randomUUID().toString());
        running.setStartedAt(startedAt);
        running.setLeaseExpiresAt(startedAt.plusSeconds(30));
        assertEquals(1, jobs.insert(running));

        assertEquals(baseline + 2.0, gauge.value());

        assertEquals(1, jobs.deleteById(queued.getId()));
        assertEquals(1, jobs.deleteById(running.getId()));
        assertEquals(baseline, gauge.value());
    }

    @Test
    void expiredFirstLeaseIsRequeuedButExpiredSecondLeaseFailsPermanently() {
        ScreenshotJob retryable = insertRunningJob(1, UUID.randomUUID().toString());
        ScreenshotJob exhausted = insertRunningJob(2, UUID.randomUUID().toString());

        manager.recoverExpiredLeases();

        ScreenshotJob requeued = jobs.selectById(retryable.getId());
        ScreenshotJob failed = jobs.selectById(exhausted.getId());
        assertAll(
                () -> assertEquals(0, requeued.getStatus()),
                () -> assertEquals(1, requeued.getAttemptCount()),
                () -> assertNull(requeued.getRunToken()),
                () -> assertNull(requeued.getLeaseExpiresAt()),
                () -> assertNull(requeued.getCompletedAt()),
                () -> assertEquals(3, failed.getStatus()),
                () -> assertEquals(2, failed.getAttemptCount()),
                () -> assertEquals("WORKER_INTERRUPTED", failed.getFailureCode()),
                () -> assertNotNull(failed.getCompletedAt())
        );
    }

    @Test
    void oldRunTokenCannotCompleteAfterLeaseRecoveryAndSecondClaim() {
        String staleToken = UUID.randomUUID().toString();
        ScreenshotJob original = insertRunningJob(1, staleToken);
        manager.recoverExpiredLeases();

        ScreenshotJob claimedAgain = manager.claimNextJob();
        assertNotNull(claimedAgain);
        assertEquals(original.getId(), claimedAgain.getId());
        assertEquals(2, claimedAgain.getAttemptCount());
        assertNotEquals(staleToken, claimedAgain.getRunToken());

        LocalDateTime completedAt = utcNow();
        assertEquals(0, jobs.completeSucceededIfRunTokenMatches(
                original.getId(), staleToken, 3L, "a".repeat(64),
                0, false, completedAt.plusHours(1), completedAt));
        assertEquals(1, jobs.completeSucceededIfRunTokenMatches(
                original.getId(), claimedAgain.getRunToken(), 3L, "b".repeat(64),
                0, false, completedAt.plusHours(1), completedAt));

        ScreenshotJob succeeded = jobs.selectById(original.getId());
        assertEquals(2, succeeded.getStatus());
        assertEquals("b".repeat(64), succeeded.getResultSha256());
    }

    @Test
    void expiryDeletesPublishedPngBeforeMetadataCleanupDeletesTheRow() {
        LocalDateTime completedAt = utcNow().minusHours(25);
        ScreenshotJob job = terminalSucceededJob(completedAt);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        results.saveAtomically(job.getJobId(), png);
        assertTrue(Files.exists(RESULT_DIRECTORY.resolve(job.getJobId() + ".png")));

        manager.expireResults();

        assertEquals(4, jobs.selectById(job.getId()).getStatus());
        assertFalse(Files.exists(RESULT_DIRECTORY.resolve(job.getJobId() + ".png")));

        manager.cleanupMetadata();

        assertNull(jobs.selectById(job.getId()));
    }

    @Test
    void quotaAndQueuedInsertRollBackTogetherWhenAdmissionCallbackFails() {
        ApiKeyIdentity identity = new ApiKeyIdentity(key.getId(), application.getId());
        String jobId = UUID.randomUUID().toString();

        assertThrows(TestAdmissionFailure.class, () -> quotaService.consumeOnceAndCommit(identity, () -> {
            ScreenshotJob job = queuedJob(jobId);
            assertEquals(1, jobs.insert(job));
            throw new TestAdmissionFailure();
        }));

        assertEquals(0L, jobs.selectCount(Wrappers.<ScreenshotJob>lambdaQuery()
                .eq(ScreenshotJob::getJobId, jobId)));
        assertEquals(0L, quotas.selectCount(Wrappers.<ApplicationQuota>lambdaQuery()
                .eq(ApplicationQuota::getApplicationId, application.getId())));
    }

    private ScreenshotJob insertRunningJob(int attemptCount, String runToken) {
        LocalDateTime now = utcNow();
        ScreenshotJob job = queuedJob(UUID.randomUUID().toString());
        job.setStatus(1);
        job.setAttemptCount(attemptCount);
        job.setRunToken(runToken);
        job.setStartedAt(now.minusMinutes(1));
        job.setLeaseExpiresAt(now.minusSeconds(1));
        assertEquals(1, jobs.insert(job));
        return job;
    }

    private ScreenshotJob terminalSucceededJob(LocalDateTime completedAt) {
        ScreenshotJob job = queuedJob(UUID.randomUUID().toString());
        job.setStatus(2);
        job.setAttemptCount(1);
        job.setStartedAt(completedAt.minusSeconds(10));
        job.setCompletedAt(completedAt);
        job.setResultSizeBytes(4L);
        job.setResultSha256("c".repeat(64));
        job.setBlockedResources(0);
        job.setResourceLimitReached(false);
        job.setResultExpiresAt(completedAt.plusHours(1));
        assertEquals(1, jobs.insert(job));
        return job;
    }

    private ScreenshotJob queuedJob(String jobId) {
        LocalDateTime now = utcNow();
        ScreenshotJob job = new ScreenshotJob();
        job.setJobId(jobId);
        job.setApiKeyId(key.getId());
        job.setTargetUrl(URL);
        job.setStatus(0);
        job.setAttemptCount(0);
        job.setCreatedAt(now.minusMinutes(2));
        job.setUpdatedAt(now.minusMinutes(2));
        return job;
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static final class TestAdmissionFailure extends RuntimeException {
    }
}
