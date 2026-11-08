package com.nexus.testing;

import com.nexus.apikey.dto.CreateApiKeyRequest;
import com.nexus.apikey.dto.DeleteApiKeyRequest;
import com.nexus.apikey.dto.UpdateApiKeyRequest;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.apikey.service.ApiKeyService;
import com.nexus.application.dto.CreateApplicationRequest;
import com.nexus.application.service.ApplicationService;
import com.nexus.auth.dto.RegisterRequest;
import com.nexus.auth.service.RegistrationService;
import com.nexus.job.mapper.ScreenshotJobMapper;
import com.nexus.user.mapper.UserMapper;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real UTC sessions, including Flyway's independent data source; all fixture writes roll back. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.user=${spring.datasource.username}",
        "spring.flyway.password=${spring.datasource.password}",
        "nexus.webhook.scheduling.enabled=false"})
@Import(UtcPersistenceIntegrationTests.FixedTime.class)
@Transactional
class UtcPersistenceIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-08T20:30:00.123Z");
    private static final LocalDateTime UTC_NOW = LocalDateTime.parse("2026-11-08T20:30:00.123");
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired RegistrationService registration;
    @Autowired ApplicationService applications;
    @Autowired ApiKeyService keys;
    @Autowired ApiKeyMapper keyMapper;
    @Autowired UserMapper users;
    @Autowired ScreenshotJobMapper jobs;
    @Autowired WebhookOutboxMapper outbox;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean @Primary Clock persistenceClock() {
            return Clock.fixed(NOW, ZoneId.of("America/Los_Angeles"));
        }
    }

    @Test
    void applicationAndIndependentFlywayConnectionsBothUseUtc() throws Exception {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())", Integer.class));
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT DATABASE(), TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())")) {
            assertTrue(result.next());
            assertEquals("nexus_test", result.getString(1));
            assertEquals(0, result.getInt(2));
        }
    }

    @Test
    void registrationKeyLifecycleAndCascadeStoreTheSameUtcInstant() {
        long[] ids = createOwnerAndApplication();
        var created = keys.createMyApiKey(ids[0], new CreateApiKeyRequest("utc-key", ids[1]));
        assertEquals(NOW, created.createdAt());
        var user = users.selectById(ids[0]);
        assertEquals(UTC_NOW, user.getCreatedAt());
        assertEquals(UTC_NOW, user.getUpdatedAt());
        var rotated = keys.rotateMyApiKey(ids[0], created.id(), created.publicId());
        assertEquals(NOW, rotated.updatedAt());
        var updated = keys.updateMyApiKey(ids[0], new UpdateApiKeyRequest("renamed", ids[1], created.id(), 1));
        assertEquals(NOW, updated.createdAt());
        assertEquals(NOW, updated.updatedAt());
        String code = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO short_links (api_key_id,name,original_url,short_code,expires_at) VALUES (?,?,?,?,?)",
                created.id(), "utc-link", "https://weibo.com/1", code, UTC_NOW.plusDays(1));
        keys.deleteMyApiKey(ids[0], new DeleteApiKeyRequest(ids[1], created.id()));
        var deleted = keyMapper.selectById(created.id());
        assertEquals(UTC_NOW, deleted.getDeletedAt());
        assertEquals(UTC_NOW, deleted.getUpdatedAt());
        assertEquals(UTC_NOW, jdbc.queryForObject("SELECT deleted_at FROM short_links WHERE short_code=?",
                LocalDateTime.class, code));
        assertEquals(UTC_NOW, jdbc.queryForObject("SELECT updated_at FROM short_links WHERE short_code=?",
                LocalDateTime.class, code));
    }

    @Test
    void databaseDefaultsAndAutomaticUpdatesAreUtcAcrossUserJobAndWebhookTables() {
        long[] ids = createOwnerAndApplication();
        var key = keys.createMyApiKey(ids[0], new CreateApiKeyRequest("db-clock", ids[1]));
        LocalDateTime before = databaseNow();
        String email = UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO users (email,display_name,password_hash,updated_at) VALUES (?,?,?,'2000-01-01')",
                email, "defaults", "test-only-unused-hash");
        jdbc.update("UPDATE users SET display_name='changed' WHERE email=?", email);
        assertBetween(before, jdbc.queryForObject("SELECT created_at FROM users WHERE email=?", LocalDateTime.class, email));
        assertBetween(before, jdbc.queryForObject("SELECT updated_at FROM users WHERE email=?", LocalDateTime.class, email));
        jdbc.update("INSERT INTO application_webhooks (application_id,url,updated_at) VALUES (?,?,'2000-01-01')",
                ids[1], "https://example.com/hook");
        long webhookId = jdbc.queryForObject("SELECT id FROM application_webhooks WHERE application_id=?", Long.class, ids[1]);
        jdbc.update("UPDATE application_webhooks SET status=1 WHERE id=?", webhookId);
        jdbc.update("INSERT INTO webhook_secrets (webhook_id,secret_ciphertext,secret_nonce,encryption_algorithm,encryption_key_version,secret_version,status) VALUES (?,? ,?,'AES-256-GCM','v1',1,0)",
                webhookId, new byte[32], new byte[12]);
        assertBetween(before, jdbc.queryForObject("SELECT created_at FROM application_webhooks WHERE id=?", LocalDateTime.class, webhookId));
        assertBetween(before, jdbc.queryForObject("SELECT updated_at FROM application_webhooks WHERE id=?", LocalDateTime.class, webhookId));
        assertBetween(before, jdbc.queryForObject("SELECT created_at FROM webhook_secrets WHERE webhook_id=?", LocalDateTime.class, webhookId));
        String jobId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO screenshot_jobs (job_id,api_key_id,target_url,updated_at) VALUES (?,?,?,'2000-01-01')",
                jobId, key.id(), "https://blog.csdn.net/test/article/details/1");
        long internalJobId = jdbc.queryForObject("SELECT id FROM screenshot_jobs WHERE job_id=?", Long.class, jobId);
        String runToken = UUID.randomUUID().toString();
        LocalDateTime started = databaseNow();
        assertEquals(1, jobs.markRunningIfQueued(internalJobId, runToken, started.plusSeconds(30), started));
        assertBetween(before, jdbc.queryForObject("SELECT created_at FROM screenshot_jobs WHERE id=?", LocalDateTime.class, internalJobId));
        assertBetween(before, jdbc.queryForObject("SELECT updated_at FROM screenshot_jobs WHERE id=?", LocalDateTime.class, internalJobId));
        assertEquals(1, jobs.markFailedIfRunTokenMatches(internalJobId, runToken, "TEST_FAILURE", databaseNow()));
        String eventId = UUID.randomUUID().toString();
        assertEquals(1, outbox.insertScreenshotCompletion(internalJobId, eventId));
        assertBetween(before, jdbc.queryForObject("SELECT created_at FROM webhook_outbox WHERE event_id=?", LocalDateTime.class, eventId));
        assertBetween(before, jdbc.queryForObject("SELECT next_attempt_at FROM webhook_outbox WHERE event_id=?", LocalDateTime.class, eventId));
        jdbc.update("UPDATE webhook_outbox SET updated_at='2000-01-01' WHERE event_id=?", eventId);
        jdbc.update("UPDATE webhook_outbox SET last_failure_code='TEST_UPDATE' WHERE event_id=?", eventId);
        assertBetween(before, jdbc.queryForObject("SELECT updated_at FROM webhook_outbox WHERE event_id=?", LocalDateTime.class, eventId));
    }

    private long[] createOwnerAndApplication() {
        String email = UUID.randomUUID() + "@example.com";
        registration.register(new RegisterRequest(email, "utc-owner", "test-only-password"));
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
        return new long[]{userId, applications.createApplication(userId, new CreateApplicationRequest("utc-app")).id()};
    }

    private LocalDateTime databaseNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class);
    }

    private void assertBetween(LocalDateTime before, LocalDateTime actual) {
        assertFalse(actual.isBefore(before), "automatic timestamp predates the write");
        assertFalse(actual.isAfter(databaseNow()), "automatic timestamp is ahead of database UTC");
    }
}
