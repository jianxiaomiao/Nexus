package com.nexus.job.manager;

import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.mapper.ScreenshotJobMapper;
import com.nexus.job.store.ScreenshotResultStore;
import com.nexus.screenshot.exception.ScreenshotException;
import com.nexus.screenshot.service.ScreenshotRenderer;
import com.nexus.screenshot.service.ScreenshotResult;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import com.nexus.webhook.entity.ApplicationWebhook;
import com.nexus.webhook.entity.WebhookOutbox;
import com.nexus.webhook.mapper.ApplicationWebhookMapper;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "nexus.webhook.scheduling.enabled=false")
class ScreenshotWebhookAtomicIntegrationTests {
    @DynamicPropertySource static void resultDirectory(DynamicPropertyRegistry properties) {
        properties.add("nexus.screenshot-jobs.result-directory", () -> Path.of("target", "test-data", "webhook-atomic").toAbsolutePath().toString());
    }
    @MockitoBean ScreenshotJobScheduler screenshotScheduler;
    @MockitoBean ScreenshotRenderer renderer;
    @Autowired ScreenshotJobManager manager;
    @Autowired ScreenshotJobMapper jobs;
    @Autowired ScreenshotResultStore results;
    @Autowired UserMapper users;
    @Autowired ApplicationMapper applications;
    @Autowired ApiKeyMapper keys;
    @Autowired ApiKeyCredentialGenerator credentials;
    @Autowired ApplicationWebhookMapper configs;
    @Autowired WebhookOutboxMapper outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    User user; Application app; ApiKey key;

    @BeforeEach void setup() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("nexus_test_user@"));
        user = new User(); user.setEmail(UUID.randomUUID() + "@example.test"); user.setDisplayName("atomic test"); user.setPasswordHash("test-placeholder"); users.insert(user);
        app = new Application(); app.setOwnerUserId(user.getId()); app.setName("atomic-" + UUID.randomUUID()); applications.insert(app);
        var generated = credentials.generate(); key = new ApiKey(); key.setApplicationId(app.getId()); key.setName("atomic"); key.setPublicId(generated.publicId()); key.setSecretHash(generated.secretHash()); key.setKeyPreview(generated.keyPreview()); keys.insert(key);
    }
    @AfterEach void cleanup() {
        if (app != null && app.getId() != null) {
            for (String jobId : jdbc.queryForList("SELECT job_id FROM screenshot_jobs WHERE api_key_id = ?", String.class, key.getId())) results.deleteIfExists(jobId);
            jdbc.update("DELETE FROM webhook_outbox WHERE application_id = ?", app.getId());
            jdbc.update("DELETE FROM application_webhooks WHERE application_id = ?", app.getId());
            jdbc.update("DELETE FROM screenshot_jobs WHERE api_key_id = ?", key.getId());
            keys.deleteById(key.getId()); applications.deleteById(app.getId());
        }
        if (user != null && user.getId() != null) users.deleteById(user.getId());
    }
    void configure(int status) {
        var config = new ApplicationWebhook(); config.setApplicationId(app.getId()); config.setUrl("https://example.test/hook"); config.setStatus(status); configs.insert(config);
    }
    ScreenshotJob running(int attempts, boolean expired) {
        var now = LocalDateTime.now(ZoneOffset.UTC); var job = new ScreenshotJob();
        job.setJobId(UUID.randomUUID().toString()); job.setApiKeyId(key.getId()); job.setTargetUrl("https://blog.csdn.net/article");
        job.setStatus(1); job.setAttemptCount(attempts); job.setRunToken(UUID.randomUUID().toString());
        job.setCreatedAt(now.minusMinutes(2)); job.setUpdatedAt(now.minusMinutes(2)); job.setStartedAt(now.minusMinutes(1));
        job.setLeaseExpiresAt(expired ? now.minusSeconds(1) : now.plusMinutes(2)); jobs.insert(job); return job;
    }
    WebhookOutbox event(ScreenshotJob job) {
        return outbox.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<WebhookOutbox>lambdaQuery().eq(WebhookOutbox::getJobId, job.getJobId()));
    }
    void duplicate(ScreenshotJob job) {
        var row = new WebhookOutbox(); row.setEventId(UUID.randomUUID().toString()); row.setJobId(job.getJobId());
        row.setApplicationId(app.getId()); row.setEventType("screenshot.completed"); row.setEventStatus(0);
        row.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC)); row.setQueryUrl("/test"); row.setStatus(0);
        row.setAttemptCount(0L); row.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC)); outbox.insert(row);
    }
    @Test void successAndFailureCommitOneMatchingEvent() {
        configure(0);
        var success = running(1, false); when(renderer.render(any())).thenReturn(new ScreenshotResult(new byte[]{1,2,3}, 0));
        assertTrue(manager.executeClaimedJob(success));
        var saved = jobs.selectById(success.getId()); var succeededEvent = event(success);
        assertEquals(2, saved.getStatus()); assertNotNull(succeededEvent); assertEquals(0, succeededEvent.getEventStatus());
        assertEquals(saved.getResultSha256(), succeededEvent.getResultSha256()); assertEquals(saved.getResultSizeBytes(), succeededEvent.getResultSizeBytes());
        assertTrue(succeededEvent.getQueryUrl().endsWith(success.getJobId()));
        var failed = running(1, false); when(renderer.render(any())).thenThrow(new ScreenshotException(ScreenshotException.Kind.TIMEOUT));
        assertFalse(manager.executeClaimedJob(failed));
        assertEquals(3, jobs.selectById(failed.getId()).getStatus()); assertEquals(1, event(failed).getEventStatus());
        assertEquals("SCREENSHOT_TIMEOUT", event(failed).getFailureCode());
        assertFalse(manager.executeClaimedJob(failed));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM webhook_outbox WHERE job_id = ?", Integer.class, failed.getJobId()));
    }
    @Test void existingEventForcesJobUpdateToRollBack() {
        configure(0); var job = running(1, false); duplicate(job);
        when(renderer.render(any())).thenReturn(new ScreenshotResult(new byte[]{1,2,3}, 0));
        assertFalse(manager.executeClaimedJob(job));
        assertEquals(1, jobs.selectById(job.getId()).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM webhook_outbox WHERE job_id = ?", Integer.class, job.getJobId()));
    }
    @Test void staleWorkerCannotCreateAnEvent() {
        configure(0); var job = running(1, false); assertNull(event(job));
        jdbc.update("UPDATE screenshot_jobs SET run_token = ? WHERE id = ?", UUID.randomUUID().toString(), job.getId());
        when(renderer.render(any())).thenThrow(new ScreenshotException(ScreenshotException.Kind.TIMEOUT));
        assertFalse(manager.executeClaimedJob(job));
        assertEquals(1, jobs.selectById(job.getId()).getStatus()); assertNull(event(job));
    }
    @Test void duplicateEventRollsBackKnownFailureAndExhaustedRecovery() {
        configure(0); var failed = running(1, false); duplicate(failed);
        when(renderer.render(any())).thenThrow(new ScreenshotException(ScreenshotException.Kind.TIMEOUT));
        assertThrows(RuntimeException.class, () -> manager.executeClaimedJob(failed));
        assertEquals(1, jobs.selectById(failed.getId()).getStatus());
        var exhausted = running(2, true); duplicate(exhausted);
        assertThrows(RuntimeException.class, () -> manager.recoverExpiredLeases());
        assertEquals(1, jobs.selectById(exhausted.getId()).getStatus());
    }
    @Test void failedRecoveryDoesNotUndoEarlierCommittedJob() {
        configure(0);
        var first = running(2, true); var poisoned = running(2, true); var later = running(2, true);
        jdbc.update("UPDATE screenshot_jobs SET lease_expires_at = UTC_TIMESTAMP() - INTERVAL 30 SECOND WHERE id = ?", first.getId());
        jdbc.update("UPDATE screenshot_jobs SET lease_expires_at = UTC_TIMESTAMP() - INTERVAL 20 SECOND WHERE id = ?", poisoned.getId());
        jdbc.update("UPDATE screenshot_jobs SET lease_expires_at = UTC_TIMESTAMP() - INTERVAL 10 SECOND WHERE id = ?", later.getId());
        duplicate(poisoned);
        assertThrows(RuntimeException.class, () -> manager.recoverExpiredLeases());
        assertEquals(3, jobs.selectById(first.getId()).getStatus()); assertNotNull(event(first));
        assertEquals(1, jobs.selectById(poisoned.getId()).getStatus()); assertEquals(1, jobs.selectById(later.getId()).getStatus());
    }
    @Test void missingConfigSkipsEventAndDisabledConfigRetainsPendingEvent() {
        var without = running(1, false);
        when(renderer.render(any())).thenThrow(new ScreenshotException(ScreenshotException.Kind.TIMEOUT));
        assertFalse(manager.executeClaimedJob(without));
        assertEquals(3, jobs.selectById(without.getId()).getStatus()); assertNull(event(without));
        configure(1); var disabled = running(1, false);
        assertFalse(manager.executeClaimedJob(disabled));
        assertEquals(3, jobs.selectById(disabled.getId()).getStatus()); assertEquals(0, event(disabled).getStatus());
    }
    @Test void exhaustedRecoveryCreatesOnlyOneEventAndStaleTokenCannotPublish() {
        configure(0); var job = running(2, true);
        manager.recoverExpiredLeases(); manager.recoverExpiredLeases();
        assertEquals(3, jobs.selectById(job.getId()).getStatus()); assertEquals("WORKER_INTERRUPTED", event(job).getFailureCode());
        when(renderer.render(any())).thenThrow(new ScreenshotException(ScreenshotException.Kind.TIMEOUT));
        assertFalse(manager.executeClaimedJob(job));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM webhook_outbox WHERE job_id = ?", Integer.class, job.getJobId()));
    }
}
