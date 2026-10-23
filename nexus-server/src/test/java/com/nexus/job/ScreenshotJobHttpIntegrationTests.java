package com.nexus.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.credential.GeneratedApiKey;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.mapper.ScreenshotJobMapper;
import com.nexus.job.store.ScreenshotResultStore;
import com.nexus.quota.QuotaTestCleanup;
import com.nexus.quota.entity.ApplicationQuota;
import com.nexus.quota.mapper.ApplicationQuotaMapper;
import com.nexus.screenshot.service.ScreenshotRenderer;
import com.nexus.screenshot.service.ScreenshotResult;
import com.nexus.usage.UsageTestCleanup;
import com.nexus.usage.entity.UsageEvent;
import com.nexus.usage.mapper.UsageEventMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScreenshotJobHttpIntegrationTests {
    private static final String URL = "https://blog.csdn.net/article";
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final Path RESULT_DIRECTORY = Path.of(
            "target", "test-data", "screenshot-job-http-" + UUID.randomUUID())
            .toAbsolutePath().normalize();
    private static final String JWT_SECRET = Base64.getEncoder()
            .encodeToString(SecureRandom.getSeed(32));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> JWT_SECRET);
        registry.add("nexus.auth.jwt.issuer", () -> "nexus");
        registry.add("nexus.auth.jwt.access-token-ttl", () -> "30m");
        registry.add("nexus.screenshot-jobs.result-directory", RESULT_DIRECTORY::toString);
    }

    @Value("${local.server.port}") int port;
    @Autowired UserMapper users;
    @Autowired ApplicationMapper applications;
    @Autowired ApiKeyMapper keys;
    @Autowired ApplicationQuotaMapper quotas;
    @Autowired ScreenshotJobMapper jobs;
    @Autowired ScreenshotResultStore resultStore;
    @Autowired UsageEventMapper events;
    @Autowired ApiKeyCredentialGenerator credentials;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("usageExecutor") Executor usageExecutor;
    @MockitoBean ScreenshotRenderer renderer;

    private final HttpClient http = HttpClient.newHttpClient();
    private User owner;
    private Application application;
    private ApiKey key;
    private ApiKey otherKey;
    private GeneratedApiKey credential;
    private GeneratedApiKey otherCredential;

    @BeforeEach
    void fixtures() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)
                .startsWith("nexus_test_user@"));

        owner = new User();
        owner.setEmail("screenshot-job-" + UUID.randomUUID() + "@example.test");
        owner.setDisplayName("Screenshot job integration");
        owner.setPasswordHash("test-only-placeholder");
        users.insert(owner);

        application = new Application();
        application.setOwnerUserId(owner.getId());
        application.setName("screenshot-job-" + UUID.randomUUID());
        applications.insert(application);

        credential = credentials.generate();
        key = insertKey("job owner", credential);
        otherCredential = credentials.generate();
        otherKey = insertKey("other key", otherCredential);
        when(renderer.render(URI.create(URL))).thenReturn(new ScreenshotResult(PNG, 2, true));
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        if (application != null && application.getId() != null) {
            for (ApiKey apiKey : keys.selectList(Wrappers.<ApiKey>lambdaQuery()
                    .eq(ApiKey::getApplicationId, application.getId()))) {
                for (ScreenshotJob job : jobs.selectList(Wrappers.<ScreenshotJob>lambdaQuery()
                        .eq(ScreenshotJob::getApiKeyId, apiKey.getId()))) {
                    resultStore.deleteIfExists(job.getJobId());
                    jobs.deleteById(job.getId());
                }
                UsageTestCleanup.deleteEventsAfterPendingWrites(
                        usageExecutor, events, apiKey.getId());
                keys.deleteById(apiKey.getId());
            }
            QuotaTestCleanup.deletePeriods(quotas, application.getId());
            applications.deleteById(application.getId());
        }
        if (owner != null && owner.getId() != null) {
            users.deleteById(owner.getId());
        }
    }

    @AfterAll
    static void removeResultDirectory() throws Exception {
        Files.deleteIfExists(RESULT_DIRECTORY);
    }

    @Test
    void submitPollAndDownloadUseRealHttpMySqlSchedulerAndFileStore() throws Exception {
        HttpResponse<byte[]> created = request("POST", "/v1/web/screenshot-jobs",
                credential.plaintextKey(), "{\"url\":\"" + URL + "\"}");
        assertEquals(201, created.statusCode());
        JsonNode createdJson = json.readTree(created.body());
        String jobId = createdJson.at("/data/jobId").asText();
        assertEquals("QUEUED", createdJson.at("/data/status").asText());
        assertEquals("/v1/web/screenshot-jobs/" + jobId,
                created.headers().firstValue("Location").orElseThrow());

        HttpResponse<byte[]> forbiddenByOwnership = request("GET",
                "/v1/web/screenshot-jobs/" + jobId, otherCredential.plaintextKey(), null);
        assertEquals(404, forbiddenByOwnership.statusCode());
        assertEquals("SCREENSHOT_JOB_NOT_FOUND",
                json.readTree(forbiddenByOwnership.body()).at("/code").asText());

        JsonNode succeeded = awaitSucceeded(jobId);
        assertEquals(1, succeeded.at("/data/attemptCount").asInt());
        assertEquals(PNG.length, succeeded.at("/data/resultSizeBytes").asLong());
        assertEquals(2, succeeded.at("/data/blockedResources").asInt());
        assertTrue(succeeded.at("/data/resourceLimitReached").asBoolean());
        assertEquals(64, succeeded.at("/data/resultSha256").asText().length());

        HttpResponse<byte[]> result = request("GET",
                "/v1/web/screenshot-jobs/" + jobId + "/result",
                credential.plaintextKey(), null);
        assertEquals(200, result.statusCode());
        assertEquals("image/png", result.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", result.headers().firstValue("Cache-Control").orElseThrow());
        assertArrayEquals(PNG, result.body());

        List<ApplicationQuota> periods = quotas.selectList(Wrappers.<ApplicationQuota>lambdaQuery()
                .eq(ApplicationQuota::getApplicationId, application.getId()));
        assertEquals(1, periods.size());
        assertEquals(1, periods.getFirst().getUsedCount());
        awaitSingleUsageEvent();
        verify(renderer).render(URI.create(URL));
    }

    private ApiKey insertKey(String name, GeneratedApiKey generated) {
        ApiKey apiKey = new ApiKey();
        apiKey.setApplicationId(application.getId());
        apiKey.setName(name);
        apiKey.setPublicId(generated.publicId());
        apiKey.setSecretHash(generated.secretHash());
        apiKey.setKeyPreview(generated.keyPreview());
        keys.insert(apiKey);
        return apiKey;
    }

    private JsonNode awaitSucceeded(String jobId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            HttpResponse<byte[]> response = request("GET",
                    "/v1/web/screenshot-jobs/" + jobId, credential.plaintextKey(), null);
            assertEquals(200, response.statusCode());
            JsonNode body = json.readTree(response.body());
            String status = body.at("/data/status").asText();
            if ("SUCCEEDED".equals(status)) {
                return body;
            }
            if ("FAILED".equals(status)) {
                fail("screenshot job failed: " + body.at("/data/failureCode").asText());
            }
            // /v1/* 共享每秒 10 次平滑限流，轮询间隔不能制造测试自己的 429。
            Thread.sleep(150);
        }
        return fail("screenshot job did not succeed in time");
    }

    private void awaitSingleUsageEvent() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            List<UsageEvent> recorded = events.selectList(Wrappers.<UsageEvent>lambdaQuery()
                    .eq(UsageEvent::getApiKeyId, key.getId()));
            if (!recorded.isEmpty()) {
                assertEquals(1, recorded.size());
                assertEquals("web.screenshot", recorded.getFirst().getApiCode());
                assertEquals(201, recorded.getFirst().getHttpStatusCode());
                return;
            }
            Thread.sleep(50);
        }
        fail("screenshot job Usage event was not written");
    }

    private HttpResponse<byte[]> request(String method, String path,
                                         String plaintextKey, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "ApiKey " + plaintextKey);
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
