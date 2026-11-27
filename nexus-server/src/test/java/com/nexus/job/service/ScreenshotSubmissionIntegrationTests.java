package com.nexus.job.service;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.job.exception.ScreenshotSubmissionException;
import com.nexus.job.manager.ScreenshotJobScheduler;
import com.nexus.application.service.ApplicationService;
import com.nexus.application.dto.UpdateApplicationRequest;
import com.nexus.apikey.service.ApiKeyService;
import com.nexus.apikey.dto.UpdateApiKeyRequest;
import com.nexus.common.web.ResourceChangedException;
import com.nexus.job.mapper.ScreenshotJobMapper;
import com.nexus.job.store.ScreenshotResultStore;
import com.nexus.quota.service.ApplicationQuotaService;
import com.nexus.screenshot.service.ScreenshotUrlPolicy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Instant;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** No HTTP fetch or screenshot worker is started; transactions run against a dedicated test MySQL. */
@SpringBootTest(properties = {"nexus.webhook.scheduling.enabled=false", "nexus.auth.session.cleanup-enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ScreenshotSubmissionIntegrationTests {
    @MockitoBean ScreenshotJobScheduler scheduler;
    @Autowired ScreenshotJobService service;
    @Autowired ApplicationService applicationService;
    @Autowired ApiKeyService apiKeyService;
    @Autowired ScreenshotJobMapper jobMapper;
    @Autowired ScreenshotUrlPolicy urlPolicy;
    @Autowired ApplicationQuotaService quotaService;
    @Autowired ScreenshotResultStore resultStore;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    long user, application, key, otherKey;
    String requestKey;
    private static final String URL = "https://blog.csdn.net/article";

    @BeforeEach void createOwnedFixtures() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        String suffix = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users(email,display_name,password_hash) VALUES(?,?,?)", suffix + "@example.test", "robustness fixture", "test-only-placeholder");
        user = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, suffix + "@example.test");
        jdbc.update("INSERT INTO applications(owner_user_id,name) VALUES(?,?)", user, suffix);
        application = jdbc.queryForObject("SELECT id FROM applications WHERE owner_user_id=?", Long.class, user);
        key = insertKey("primary"); otherKey = insertKey("other");
        requestKey = Instant.now().toEpochMilli() + "." + UUID.randomUUID();
    }
    private long insertKey(String name) {
        jdbc.update("INSERT INTO api_keys(application_id,name,public_id,secret_hash,key_preview) VALUES(?,?,?,?,?)", application, name, UUID.randomUUID().toString(), UUID.randomUUID().toString().replace("-", "").repeat(2), "fixture-preview");
        return jdbc.queryForObject("SELECT id FROM api_keys WHERE application_id=? AND name=?", Long.class, application, name);
    }
    @AfterEach void removeOnlyOwnedFixtures() {
        if (application == 0) return;
        jdbc.update("DELETE FROM screenshot_jobs WHERE api_key_id IN (?,?)", key, otherKey);
        jdbc.update("DELETE FROM application_quotas WHERE application_id=?", application);
        jdbc.update("DELETE FROM api_keys WHERE application_id=?", application);
        jdbc.update("DELETE FROM applications WHERE id=?", application);
        jdbc.update("DELETE FROM users WHERE id=?", user);
    }
    @Test void retriesAndConcurrentAdmissionsShareOneJobAndOneDebit() throws Exception {
        var identity = new ApiKeyIdentity(key, application);
        var start = new CountDownLatch(1);
        var ids = new HashSet<String>();
        try (var pool = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> { start.await(); return service.submit(identity, URL, requestKey).getJobId(); }));
            start.countDown();
            for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, ids.size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM screenshot_jobs WHERE api_key_id=?", Integer.class, key));
        assertEquals(1, jdbc.queryForObject("SELECT SUM(used_count) FROM application_quotas WHERE application_id=?", Integer.class, application));
        assertThrows(ScreenshotSubmissionException.class, () -> service.submit(identity, URL + "-changed", requestKey));
        service.submit(new ApiKeyIdentity(otherKey, application), URL, requestKey);
        assertEquals(2, jdbc.queryForObject("SELECT SUM(used_count) FROM application_quotas WHERE application_id=?", Integer.class, application));
    }
    @Test void failedInsertRollsBackQuotaAndOriginalKeyCanRecover() {
        var identity = new ApiKeyIdentity(key, application);
        // A narrowly scoped SQL failure, after quota debit, proves atomic rollback without a real worker.
        jdbc.execute("ALTER TABLE screenshot_jobs ADD CONSTRAINT robustness_reject_fixture CHECK (target_url <> 'https://blog.csdn.net/article')");
        try { assertThrows(RuntimeException.class, () -> service.submit(identity, URL, requestKey)); }
        finally { jdbc.execute("ALTER TABLE screenshot_jobs DROP CHECK robustness_reject_fixture"); }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM application_quotas WHERE application_id=?", Integer.class, application));
        service.submit(identity, URL, requestKey);
        assertEquals(1, jdbc.queryForObject("SELECT SUM(used_count) FROM application_quotas WHERE application_id=?", Integer.class, application));
    }
    @Test void independentAdmissionLocksStillDeduplicateAtTheDatabaseBoundary() throws Exception {
        // Both initial reads finish before either insert. The service objects share no admission lock.
        var reads = new AtomicInteger();
        var bothRead = new CyclicBarrier(2);
        var racingMapper = mock(ScreenshotJobMapper.class, delegatesTo(jobMapper));
        doAnswer(invocation -> {
            var result = jobMapper.selectOne(invocation.getArgument(0));
            if (reads.getAndIncrement() < 2) {
                assertNull(result);
                bothRead.await(10, TimeUnit.SECONDS);
            }
            return result;
        }).when(racingMapper).selectOne(any());
        var first = new ScreenshotJobService(racingMapper, urlPolicy, quotaService, resultStore, clock);
        var second = new ScreenshotJobService(racingMapper, urlPolicy, quotaService, resultStore, clock);
        var identity = new ApiKeyIdentity(key, application);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> first.submit(identity, URL, requestKey).getJobId());
            var b = pool.submit(() -> second.submit(identity, URL, requestKey).getJobId());
            assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM screenshot_jobs WHERE api_key_id=?", Integer.class, key));
        assertEquals(1, jdbc.queryForObject("SELECT SUM(used_count) FROM application_quotas WHERE application_id=?", Integer.class, application));
    }
    @Test void staleApplicationAndKeyEditsCannotOverwriteNewerData() {
        var app = applicationService.listMyApplications(user, 1, 10, application).records().getFirst();
        var updated = applicationService.updateMyApplication(user, new UpdateApplicationRequest(application, "new name", null, app.updatedAt()));
        assertTrue(updated.updatedAt().isAfter(app.updatedAt()));
        assertThrows(ResourceChangedException.class, () -> applicationService.updateMyApplication(user, new UpdateApplicationRequest(application, "stale name", null, app.updatedAt())));
        var original = apiKeyService.listMyApiKeys(user, application, 1, 10, key).records().getFirst();
        var changed = apiKeyService.updateMyApiKey(user, new UpdateApiKeyRequest("new key", application, key, null, original.updatedAt()));
        assertTrue(changed.updatedAt().isAfter(original.updatedAt()));
        assertThrows(ResourceChangedException.class, () -> apiKeyService.updateMyApiKey(user, new UpdateApiKeyRequest("stale key", application, key, null, original.updatedAt())));
    }
}
