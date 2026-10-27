package com.nexus.usage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.quota.entity.ApplicationQuota;
import com.nexus.quota.mapper.ApplicationQuotaMapper;
import com.nexus.usage.entity.UsageEvent;
import com.nexus.usage.mapper.UsageEventMapper;
import com.nexus.usage.service.UsageEventWriter;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QuotaUsageLockOrderIntegrationTests {
    @MockitoSpyBean ApplicationMapper applications;
    @Autowired ApiKeyMapper apiKeys;
    @Autowired ApplicationQuotaMapper quotas;
    @Autowired UsageEventMapper usageEvents;
    @Autowired UsageEventWriter usageWriter;
    @Autowired UserMapper users;
    @Autowired ApiKeyCredentialGenerator credentials;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;

    private Long ownerId;
    private Application application;
    private ApiKey apiKey;

    @BeforeEach
    void fixtures() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)
                .startsWith("nexus_test_user@"));

        User owner = new User();
        owner.setEmail("quota-usage-lock-" + UUID.randomUUID() + "@example.com");
        owner.setDisplayName("Quota Usage Lock Test");
        owner.setPasswordHash("test-only-placeholder");
        users.insert(owner);
        ownerId = owner.getId();

        application = new Application();
        application.setOwnerUserId(ownerId);
        application.setName("quota-usage-lock-" + UUID.randomUUID());
        applications.insert(application);

        var credential = credentials.generate();
        apiKey = new ApiKey();
        apiKey.setApplicationId(application.getId());
        apiKey.setName("quota-usage-lock-key");
        apiKey.setPublicId(credential.publicId());
        apiKey.setSecretHash(credential.secretHash());
        apiKey.setKeyPreview(credential.keyPreview());
        apiKeys.insert(apiKey);
    }

    @AfterEach
    void cleanup() {
        if (ownerId == null) {
            return;
        }
        usageEvents.delete(Wrappers.<UsageEvent>lambdaQuery()
                .eq(UsageEvent::getApplicationId, application.getId()));
        quotas.delete(Wrappers.<ApplicationQuota>lambdaQuery()
                .eq(ApplicationQuota::getApplicationId, application.getId()));
        apiKeys.delete(Wrappers.<ApiKey>lambdaQuery()
                .eq(ApiKey::getApplicationId, application.getId()));
        applications.deleteById(application.getId());
        users.deleteById(ownerId);
    }

    @Test
    void usageWaitsAtApplicationSoQuotaCanContinueToApiKeyWithoutDeadlock()
            throws Exception {
        UsageEvent event = usageEvent();
        CountDownLatch writerEntered = new CountDownLatch(1);
        TransactionStatus quotaTransaction = transactions.getTransaction(
                new DefaultTransactionDefinition());
        boolean quotaCompleted = false;
        var executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);

            Application lockedApplication = applications.selectOne(
                    Wrappers.<Application>lambdaQuery()
                            .eq(Application::getId, application.getId())
                            .last("FOR UPDATE"));
            assertNotNull(lockedApplication);

            Future<?> usageWrite = executor.submit(() -> {
                writerEntered.countDown();
                usageWriter.insertWithRetry(event);
            });
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS));
            verify(applications, timeout(5_000))
                    .selectIdForShare(application.getId());
            assertFalse(usageWrite.isDone());

            ApiKey lockedKey = apiKeys.selectOne(Wrappers.<ApiKey>lambdaQuery()
                    .eq(ApiKey::getId, apiKey.getId())
                    .eq(ApiKey::getApplicationId, application.getId())
                    .last("FOR UPDATE"));
            assertNotNull(lockedKey);

            transactions.commit(quotaTransaction);
            quotaCompleted = true;
            usageWrite.get(5, TimeUnit.SECONDS);
        } finally {
            if (!quotaCompleted && !quotaTransaction.isCompleted()) {
                transactions.rollback(quotaTransaction);
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(1L, usageEvents.selectCount(Wrappers.<UsageEvent>lambdaQuery()
                .eq(UsageEvent::getEventId, event.getEventId())));
    }

    private UsageEvent usageEvent() {
        UsageEvent event = new UsageEvent();
        event.setApiKeyId(apiKey.getId());
        event.setApplicationId(application.getId());
        event.setApiCode("uuid.generate");
        event.setHttpStatusCode(200);
        event.setDurationMs(1);
        event.setOccurredAt(LocalDateTime.now());
        event.setEventId(UUID.randomUUID().toString());
        return event;
    }
}
