package com.nexus.quota.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.auth.exception.ApiKeyForbiddenException;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import com.nexus.quota.dto.QueryQuotaRequest;
import com.nexus.quota.entity.ApplicationQuota;
import com.nexus.quota.exception.ApplicationQuotaExhaustedException;
import com.nexus.quota.mapper.ApplicationQuotaMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(ApplicationQuotaIntegrationTests.FixedTime.class)
class ApplicationQuotaIntegrationTests {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 14, 0, 0, 123_000_000);
    @Autowired ApplicationQuotaService service;
    @Autowired ApplicationMapper applications;
    @Autowired ApiKeyMapper keys;
    @Autowired ApplicationQuotaMapper quotas;
    @Autowired UserMapper users;
    @Autowired ApiKeyCredentialGenerator credentials;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    private Long ownerId;
    private Application app;
    private ApiKey key;

    @BeforeEach void fixtures() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("nexus_test_user@"));
        // 验证当前本地创建时间约定；不在测试中偷偷改写已有 DATETIME 的解释方式。
        assertEquals(28800, jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())", Integer.class));
        User owner = new User();
        owner.setEmail("quota-" + UUID.randomUUID() + "@example.com");
        owner.setDisplayName("Quota Test");
        owner.setPasswordHash("test-only-placeholder");
        users.insert(owner);
        ownerId = owner.getId();
        app = application(NOW.minusHours(1), 1000);
        key = key(app);
    }

    @AfterEach void cleanup() {
        if (ownerId == null) return;
        for (Application application : applications.selectList(Wrappers.<Application>lambdaQuery()
                .eq(Application::getOwnerUserId, ownerId))) {
            quotas.delete(Wrappers.<ApplicationQuota>lambdaQuery().eq(ApplicationQuota::getApplicationId, application.getId()));
            keys.delete(Wrappers.<ApiKey>lambdaQuery().eq(ApiKey::getApplicationId, application.getId()));
            applications.deleteById(application.getId());
        }
        users.deleteById(ownerId);
    }

    @Test void firstCallCreatesExactlyOnePeriodAndUsesOne() {
        assertEquals(0, query().usedCount());
        assertEquals(0, quotas.selectCount(Wrappers.<ApplicationQuota>lambdaQuery().eq(ApplicationQuota::getApplicationId, app.getId())));
        service.consumeOnce(identity(key));
        assertEquals(1, query().usedCount());
        assertEquals(999, query().restCount());
        assertEquals(1, quotas.selectCount(Wrappers.<ApplicationQuota>lambdaQuery().eq(ApplicationQuota::getApplicationId, app.getId())));
    }

    @Test void lastCreditIsAcceptedAndNextCallIsRejected() {
        period(app, 1000, 999);
        service.consumeOnce(identity(key));
        assertThrows(ApplicationQuotaExhaustedException.class, () -> service.consumeOnce(identity(key)));
        assertEquals(1000, query().usedCount());
    }

    @Test void concurrentFirstCallsShareOneRecordAndCannotBothConsumeLastCredit() throws Exception {
        app.setQuota(1);
        applications.updateById(app);
        ApiKey secondKey = key(app);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> compete(identity(key), ready, go));
            var second = executor.submit(() -> compete(identity(secondKey), ready, go));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, query().usedCount());
        assertEquals(1, quotas.selectCount(Wrappers.<ApplicationQuota>lambdaQuery().eq(ApplicationQuota::getApplicationId, app.getId())));
    }

    @Test void otherApplicationIsUnaffectedByConsumption() {
        Application other = application(NOW.minusHours(1), 10);
        ApiKey otherKey = key(other);
        service.consumeOnce(identity(key));
        assertEquals(0, service.queryMyApplicationQuota(ownerId, new QueryQuotaRequest(other.getId())).usedCount());
        service.consumeOnce(identity(otherKey));
        assertEquals(1, query().usedCount());
    }

    @Test void newKeyAndRotatedCredentialDoNotResetQuota() {
        service.consumeOnce(identity(key));
        ApiKey newKey = key(app);
        service.consumeOnce(identity(newKey));
        var rotated = credentials.generate();
        key.setPublicId(rotated.publicId());
        key.setSecretHash(rotated.secretHash());
        key.setKeyPreview(rotated.keyPreview());
        keys.updateById(key);
        service.consumeOnce(identity(key));
        assertEquals(3, query().usedCount());
    }

    @Test void exactBoundaryStartsNewPeriodAndPreservesOldSnapshot() {
        app.setCreatedAt(NOW.minusHours(720));
        app.setQuota(7);
        applications.updateById(app);
        period(app, 2, 2);
        assertEquals(NOW, query().periodStart());
        assertEquals(0, query().usedCount());
        service.consumeOnce(identity(key));
        assertEquals(7, query().quotaLimit());
        assertEquals(1, query().usedCount());
        assertEquals(2, quotas.selectPeriod(app.getId(), app.getCreatedAt()).getUsedCount());
    }

    @Test void longInactivitySkipsEmptyPeriods() {
        app.setCreatedAt(NOW.minusHours(720 * 4 + 1));
        applications.updateById(app);
        service.consumeOnce(identity(key));
        assertEquals(NOW.minusHours(1), query().periodStart());
        assertEquals(1, quotas.selectCount(Wrappers.<ApplicationQuota>lambdaQuery().eq(ApplicationQuota::getApplicationId, app.getId())));
    }

    @Test void disabledApplicationRemainsReadableButCannotConsume() {
        app.setStatus(1);
        applications.updateById(app);
        assertEquals(0, query().usedCount());
        assertThrows(ApiKeyForbiddenException.class, () -> service.consumeOnce(identity(key)));
        assertEquals(0, query().usedCount());
    }

    @Test void foreignOwnerAndMismatchedKeyAreRejected() {
        assertThrows(ApplicationNotFoundException.class,
                () -> service.queryMyApplicationQuota(ownerId + 1, new QueryQuotaRequest(app.getId())));
        Application other = application(NOW.minusHours(1), 10);
        assertThrows(InvalidApiKeyCredentialException.class,
                () -> service.consumeOnce(new ApiKeyIdentity(key.getId(), other.getId())));
        assertEquals(0, query().usedCount());
    }

    @Test void currentPeriodLimitIsSnapshotEvenIfApplicationConfigurationChanges() {
        service.consumeOnce(identity(key));
        app.setQuota(10);
        applications.updateById(app);
        service.consumeOnce(identity(key));
        assertEquals(1000, query().quotaLimit());
        assertEquals(998, query().restCount());
    }

    @Test void laterBusinessRollbackDoesNotRefundAlreadyCommittedCredit() {
        service.consumeOnce(identity(key));
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE applications SET name = ? WHERE id = ?", "rolled-back", app.getId());
            throw new IllegalStateException("simulated business failure");
        }));
        assertEquals(app.getName(), applications.selectById(app.getId()).getName());
        assertEquals(1, query().usedCount());
    }

    @Test void enclosingBusinessTransactionIsRejectedWithoutConsumption() {
        assertThrows(IllegalTransactionStateException.class,
                () -> new TransactionTemplate(transactions).executeWithoutResult(status -> service.consumeOnce(identity(key))));
        assertEquals(0, query().usedCount());
    }

    @Test void zeroLimitIsImmediatelyExhausted() {
        app.setQuota(0);
        applications.updateById(app);
        assertThrows(ApplicationQuotaExhaustedException.class, () -> service.consumeOnce(identity(key)));
        assertEquals(0, query().restCount());
    }

    @Test void schemaDefaultsAndConstraintsAreEnforced() {
        Application defaultApp = application(NOW.minusHours(1), null);
        assertEquals(1000, applications.selectById(defaultApp.getId()).getQuota());
        period(app, 1, 0);
        assertThrows(DuplicateKeyException.class, () -> period(app, 1, 0));
        assertCheckViolation(() -> jdbc.update("UPDATE application_quotas SET used_count = 2 WHERE application_id = ?", app.getId()),
                "chk_application_quotas_used");
        assertCheckViolation(() -> jdbc.update("UPDATE applications SET quota = -1 WHERE id = ?", app.getId()),
                "chk_applications_quota");
        assertEquals(0, query().usedCount());
    }

    @Test void batchReadsOnlyEachApplicationsCurrentPeriodAndReturnsInputOrder() {
        Application second = application(NOW.minusHours(720 * 3 + 5), 2000);
        Application third = application(NOW.minusHours(2), 300);
        period(app, 50, 17);
        period(second, 999, 999); // 历史期，必须排除。
        ApplicationQuota current = new ApplicationQuota();
        current.setApplicationId(second.getId());
        current.setPeriodStart(NOW.minusHours(5));
        current.setQuotaLimit(90);
        current.setUsedCount(8);
        quotas.insert(current);
        var responses = service.queryCurrentQuotas(java.util.List.of(second, third, app));
        assertEquals(NOW.minusHours(5), responses.get(0).periodStart());
        assertEquals(90, responses.get(0).quotaLimit());
        assertEquals(8, responses.get(0).usedCount());
        assertEquals(NOW.minusHours(2), responses.get(1).periodStart());
        assertEquals(300, responses.get(1).quotaLimit());
        assertEquals(0, responses.get(1).usedCount());
        assertEquals(50, responses.get(2).quotaLimit());
        assertEquals(17, responses.get(2).usedCount());
        assertEquals(0, quotas.selectCount(Wrappers.<ApplicationQuota>lambdaQuery().eq(ApplicationQuota::getApplicationId, third.getId())));
    }

    private int compete(ApiKeyIdentity identity, CountDownLatch ready, CountDownLatch go) throws InterruptedException {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("concurrency start timed out");
        try { service.consumeOnce(identity); return 1; }
        catch (ApplicationQuotaExhaustedException expected) { return 0; }
    }

    private void assertCheckViolation(Runnable operation, String constraint) {
        // 本地驱动的 CHECK 错误是 HY000/3819，未翻译为 DataIntegrityViolationException。
        UncategorizedSQLException exception = assertThrows(UncategorizedSQLException.class, operation::run);
        assertEquals(3819, exception.getSQLException().getErrorCode());
        assertTrue(exception.getSQLException().getMessage().contains(constraint));
    }

    private com.nexus.quota.dto.QueryQuotaResponse query() {
        return service.queryMyApplicationQuota(ownerId, new QueryQuotaRequest(app.getId()));
    }

    private Application application(LocalDateTime createdAt, Integer quota) {
        Application application = new Application();
        application.setOwnerUserId(ownerId);
        application.setName("quota-" + UUID.randomUUID());
        application.setCreatedAt(createdAt);
        application.setQuota(quota);
        applications.insert(application);
        return applications.selectById(application.getId());
    }

    private ApiKey key(Application application) {
        var generated = credentials.generate();
        ApiKey result = new ApiKey();
        result.setApplicationId(application.getId());
        result.setName("quota-" + UUID.randomUUID());
        result.setPublicId(generated.publicId());
        result.setSecretHash(generated.secretHash());
        result.setKeyPreview(generated.keyPreview());
        keys.insert(result);
        return result;
    }

    private ApiKeyIdentity identity(ApiKey value) { return new ApiKeyIdentity(value.getId(), value.getApplicationId()); }

    private void period(Application application, int limit, int used) {
        ApplicationQuota quota = new ApplicationQuota();
        quota.setApplicationId(application.getId());
        quota.setPeriodStart(application.getCreatedAt());
        quota.setQuotaLimit(limit);
        quota.setUsedCount(used);
        quotas.insert(quota);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean @Primary Clock quotaTestClock() { return Clock.fixed(NOW.toInstant(ZoneOffset.ofHours(8)), ZoneOffset.UTC); }
    }
}
