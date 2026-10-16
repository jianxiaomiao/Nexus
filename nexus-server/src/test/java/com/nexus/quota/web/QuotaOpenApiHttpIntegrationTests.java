package com.nexus.quota.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.credential.GeneratedApiKey;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.token.JwtTokenService;
import com.nexus.quota.QuotaTestCleanup;
import com.nexus.quota.entity.ApplicationQuota;
import com.nexus.quota.mapper.ApplicationQuotaMapper;
import com.nexus.screenshot.exception.ScreenshotException;
import com.nexus.screenshot.service.ScreenshotRenderer;
import com.nexus.screenshot.service.ScreenshotResult;
import com.nexus.shortlink.dto.CreateShortLinkRequest;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import com.nexus.shortlink.service.ShortCodeGenerator;
import com.nexus.usage.UsageTestCleanup;
import com.nexus.usage.mapper.UsageEventMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import com.nexus.webExtract.exception.WebPageFetchException;
import com.nexus.webExtract.service.WebPageFetcher;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QuotaOpenApiHttpIntegrationTests {
    private static final String ARTICLE = "https://blog.csdn.net/article";
    @Value("${local.server.port}") int port;
    @Autowired UserMapper users;
    @Autowired ApplicationMapper applications;
    @Autowired ApiKeyMapper keys;
    @Autowired ApplicationQuotaMapper quotas;
    @Autowired ShortLinkMapper links;
    @Autowired UsageEventMapper events;
    @Autowired ApiKeyCredentialGenerator credentials;
    @Autowired JwtTokenService tokens;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("usageExecutor") Executor usageExecutor;
    @MockitoBean WebPageFetcher fetcher;
    @MockitoBean ScreenshotRenderer renderer;
    @MockitoBean ShortCodeGenerator codes;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private User owner;
    private Application app;
    private String key;

    @BeforeEach void fixtures() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("nexus_test_user@"));
        owner = new User();
        owner.setEmail("quota-http-" + UUID.randomUUID() + "@example.test");
        owner.setDisplayName("Quota HTTP");
        owner.setPasswordHash("test-only-placeholder");
        users.insert(owner);
        app = new Application();
        app.setOwnerUserId(owner.getId());
        app.setName("quota-http-" + UUID.randomUUID());
        applications.insert(app);
        app = applications.selectById(app.getId());
        key = newKey().plaintextKey();
        when(codes.generate()).thenReturn(UUID.randomUUID().toString().replace("-", "").substring(0, 11));
    }

    @AfterEach void cleanup() throws InterruptedException {
        if (owner != null && owner.getId() != null) {
            for (Application owned : applications.selectList(Wrappers.<Application>lambdaQuery()
                    .eq(Application::getOwnerUserId, owner.getId()))) {
                for (ApiKey apiKey : keys.selectList(Wrappers.<ApiKey>lambdaQuery().eq(ApiKey::getApplicationId, owned.getId()))) {
                    UsageTestCleanup.deleteEventsAfterPendingWrites(usageExecutor, events, apiKey.getId());
                    links.delete(Wrappers.<ShortLink>lambdaQuery().eq(ShortLink::getApiKeyId, apiKey.getId()));
                    keys.deleteById(apiKey.getId());
                }
                QuotaTestCleanup.deletePeriods(quotas, owned.getId());
                applications.deleteById(owned.getId());
            }
        }
        if (owner != null && owner.getId() != null) users.deleteById(owner.getId());
    }

    @Test void uuidAndHashConsumeWhileAuthenticationAndInputFailuresDoNot() throws Exception {
        assertEquals(401, request("GET", "/v1/utils/uuid", "invalid", null).statusCode());
        assertEquals(400, call("POST", "/v1/utils/hash", "not-json").statusCode());
        assertEquals(400, call("POST", "/v1/utils/hash", "{\"value\":\"hello\",\"algorithm\":\"MD5\"}").statusCode());
        assertEquals(0, used());
        assertEquals(200, call("GET", "/v1/utils/uuid", null).statusCode());
        assertEquals(200, call("POST", "/v1/utils/hash", "{\"value\":\"hello\",\"algorithm\":\"SHA256\"}").statusCode());
        assertEquals(2, used());
        String tooLarge = json.writeValueAsString(new com.nexus.openapi.dto.HashRequest(com.nexus.openapi.dto.HashAlgorithm.SHA256, "中".repeat(1366)));
        assertEquals(400, call("POST", "/v1/utils/hash", tooLarge).statusCode());
        assertEquals(2, used());
    }

    @Test void exhaustedApplicationRejectsAllFiveChargedEndpointsBeforeBusiness() throws Exception {
        limit(1);
        assertEquals(200, call("GET", "/v1/utils/uuid", null).statusCode());
        exhausted(call("GET", "/v1/utils/uuid", null));
        exhausted(call("POST", "/v1/utils/hash", "{\"value\":\"hello\",\"algorithm\":\"SHA256\"}"));
        exhausted(call("POST", "/v1/short-links", shortBody(OffsetDateTime.now().plusMinutes(30))));
        exhausted(call("POST", "/v1/web/extract", articleBody()));
        exhausted(call("POST", "/v1/web/screenshot", articleBody()));
        assertEquals(1, used());
        verifyNoInteractions(fetcher, renderer);
        verify(codes, never()).generate();
        assertEquals(0, links.selectCount(Wrappers.<ShortLink>lambdaQuery().in(ShortLink::getApiKeyId,
                keys.selectList(Wrappers.<ApiKey>lambdaQuery().eq(ApiKey::getApplicationId, app.getId())).stream().map(ApiKey::getId).toList())));
    }

    @Test void networkAndRenderFailuresConsumeButInvalidUrlsDoNot() throws Exception {
        String invalid = "{\"url\":\"https://127.0.0.1/private\"}";
        assertEquals(400, call("POST", "/v1/web/extract", invalid).statusCode());
        assertEquals(400, call("POST", "/v1/web/screenshot", invalid).statusCode());
        assertEquals(0, used());
        verifyNoInteractions(fetcher, renderer);
        when(fetcher.fetch(any())).thenThrow(new WebPageFetchException("simulated upstream failure"));
        assertEquals(502, call("POST", "/v1/web/extract", articleBody()).statusCode());
        assertEquals(1, used());
        when(renderer.render(any())).thenThrow(new ScreenshotException(ScreenshotException.Kind.TIMEOUT));
        assertEquals(504, call("POST", "/v1/web/screenshot", articleBody()).statusCode());
        assertEquals(2, used());
        doReturn(new ScreenshotResult(new byte[]{1}, 0)).when(renderer).render(any());
        assertEquals(200, call("POST", "/v1/web/screenshot", articleBody()).statusCode());
        assertEquals(3, used());
    }

    @Test void shortLinkOnlyCreationConsumesAndOtherOperationsWorkWhenQuotaIsExhausted() throws Exception {
        limit(1);
        assertEquals(400, call("POST", "/v1/short-links", shortBody(OffsetDateTime.now().minusMinutes(1))).statusCode());
        assertEquals(0, used());
        var created = call("POST", "/v1/short-links", shortBody(OffsetDateTime.now().plusMinutes(30)));
        assertEquals(200, created.statusCode());
        long id = json.readTree(created.body()).at("/data/id").asLong();
        String code = json.readTree(created.body()).at("/data/shortCode").asText();
        assertEquals(1, used());
        assertEquals(302, request("GET", "/s/" + code, null, null).statusCode());
        assertEquals(200, call("GET", "/v1/short-links", null).statusCode());
        assertEquals(200, call("PUT", "/v1/short-links", "{\"id\":" + id + ",\"name\":\"renamed\"}").statusCode());
        String bearer = "Bearer " + tokens.issueAccessToken(owner.getId()).value();
        long keyId = json.readTree(created.body()).at("/data/apiKeyId").asLong();
        assertEquals(200, request("GET", "/api/short-links?apiKeyId=" + keyId, bearer, null).statusCode());
        assertEquals(200, call("DELETE", "/v1/short-links/" + id, null).statusCode());
        assertEquals(1, used());
    }

    @Test void shortCodeCollisionExhaustionRollsBackBusinessWithoutRefundingQuota() throws Exception {
        var first = call("POST", "/v1/short-links", shortBody(OffsetDateTime.now().plusMinutes(30)));
        assertEquals(200, first.statusCode());
        String occupied = json.readTree(first.body()).at("/data/shortCode").asText();
        long keyId = json.readTree(first.body()).at("/data/apiKeyId").asLong();
        when(codes.generate()).thenReturn(occupied);
        var failed = call("POST", "/v1/short-links", shortBody(OffsetDateTime.now().plusMinutes(30)));
        assertEquals(503, failed.statusCode());
        assertEquals("SHORT_CODE_UNAVAILABLE", json.readTree(failed.body()).at("/code").asText());
        assertEquals(2, used());
        assertEquals(1, links.selectCount(Wrappers.<ShortLink>lambdaQuery().eq(ShortLink::getApiKeyId, keyId)));
        when(codes.generate()).thenReturn(UUID.randomUUID().toString().replace("-", "").substring(0, 11));
        assertEquals(200, call("POST", "/v1/short-links", shortBody(OffsetDateTime.now().plusMinutes(30))).statusCode());
        assertEquals(3, used());
    }

    @Test void concurrentHttpRequestsFromTwoKeysHaveOneWinnerForLastCredit() throws Exception {
        limit(1);
        String second = newKey().plaintextKey();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return call("GET", "/v1/utils/uuid", null).statusCode(); });
            var other = executor.submit(() -> { start.await(); return request("GET", "/v1/utils/uuid", "ApiKey " + second, null).statusCode(); });
            start.countDown();
            int a = first.get(15, TimeUnit.SECONDS), b = other.get(15, TimeUnit.SECONDS);
            assertTrue((a == 200 && b == 429) || (a == 429 && b == 200));
        }
        assertEquals(1, used());
    }

    @Test void longScreenshotDoesNotHoldApplicationOrQuotaLock() throws Exception {
        CountDownLatch rendering = new CountDownLatch(1), release = new CountDownLatch(1);
        when(renderer.render(any())).thenAnswer(call -> {
            rendering.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("render wait timed out");
            return new ScreenshotResult(new byte[]{1}, 0);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var screenshot = executor.submit(() -> call("POST", "/v1/web/screenshot", articleBody()).statusCode());
            try {
                assertTrue(rendering.await(5, TimeUnit.SECONDS));
                assertEquals(1, used());
                assertEquals(200, call("GET", "/v1/utils/uuid", null).statusCode());
                assertEquals(2, used());
            } finally { release.countDown(); }
            assertEquals(200, screenshot.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void managerQueryDoesNotCreateOrConsumeAndDisabledHistoryRemainsReadable() throws Exception {
        String bearer = "Bearer " + tokens.issueAccessToken(owner.getId()).value();
        String path = "/api/application/" + app.getId() + "/quota";
        var empty = request("GET", path, bearer, null);
        assertEquals(200, empty.statusCode());
        assertEquals(1000, json.readTree(empty.body()).at("/data/quotaLimit").asInt());
        assertEquals(0, json.readTree(empty.body()).at("/data/usedCount").asInt());
        assertFalse(json.readTree(empty.body()).at("/data/periodEnd").asText().isBlank());
        assertEquals(0, quotas.selectCount(Wrappers.<ApplicationQuota>lambdaQuery().eq(ApplicationQuota::getApplicationId, app.getId())));
        assertEquals(200, call("GET", "/v1/utils/uuid", null).statusCode());
        app.setStatus(1);
        applications.updateById(app);
        var disabled = request("GET", path, bearer, null);
        assertEquals(200, disabled.statusCode());
        assertEquals(1, json.readTree(disabled.body()).at("/data/usedCount").asInt());
        assertEquals(999, json.readTree(disabled.body()).at("/data/restCount").asInt());
        assertEquals(403, call("GET", "/v1/utils/uuid", null).statusCode());
        assertEquals(1, used());
    }

    @Test void managerQueryRejectsMachineCredentialForeignOwnerAndDeletedApplication() throws Exception {
        String path = "/api/application/" + app.getId() + "/quota";
        assertEquals(401, request("GET", path, null, null).statusCode());
        assertEquals(401, call("GET", path, null).statusCode());
        User stranger = new User();
        stranger.setEmail("quota-stranger-" + UUID.randomUUID() + "@example.test");
        stranger.setDisplayName("Quota Stranger");
        stranger.setPasswordHash("test-only-placeholder");
        users.insert(stranger);
        try {
            String bearer = "Bearer " + tokens.issueAccessToken(stranger.getId()).value();
            assertEquals(404, request("GET", path, bearer, null).statusCode());
        } finally {
            users.deleteById(stranger.getId());
        }
        app.setIsDeleted(1);
        app.setDeletedAt(java.time.LocalDateTime.now());
        applications.updateById(app);
        String ownerBearer = "Bearer " + tokens.issueAccessToken(owner.getId()).value();
        assertEquals(404, request("GET", path, ownerBearer, null).statusCode());
        assertEquals(0, used());
    }

    @Test void managerQueryRejectsNonPositiveId() throws Exception {
        String bearer = "Bearer " + tokens.issueAccessToken(owner.getId()).value();
        var response = request("GET", "/api/application/0/quota", bearer, null);
        assertEquals(400, response.statusCode());
        assertEquals("INVALID_APPLICATION_ID", json.readTree(response.body()).at("/code").asText());
    }

    @Test void paginatedListIncludesQuotaOnlyForCurrentPageWithoutCreatingPeriods() throws Exception {
        Application last = null;
        for (int index = 1; index <= 10; index++) {
            Application extra = new Application();
            extra.setOwnerUserId(owner.getId());
            extra.setName("quota-page-" + index + "-" + UUID.randomUUID());
            extra.setCreatedAt(app.getCreatedAt().minusHours(index));
            extra.setQuota(1000 + index);
            applications.insert(extra);
            last = extra;
        }
        String bearer = "Bearer " + tokens.issueAccessToken(owner.getId()).value();
        var first = request("GET", "/api/application?current=1&size=10", bearer, null);
        assertEquals(200, first.statusCode());
        var firstData = json.readTree(first.body()).at("/data");
        assertEquals(11, firstData.at("/total").asInt());
        assertEquals(10, firstData.at("/records").size());
        assertEquals(app.getId().longValue(), firstData.at("/records/0/id").asLong());
        assertEquals(1000, firstData.at("/records/0/quota/quotaLimit").asInt());
        var second = request("GET", "/api/application?current=2&size=10", bearer, null);
        assertEquals(200, second.statusCode());
        var secondData = json.readTree(second.body()).at("/data");
        assertEquals(2, secondData.at("/current").asInt());
        assertEquals(1, secondData.at("/records").size());
        assertEquals(last.getId().longValue(), secondData.at("/records/0/id").asLong());
        assertEquals(1010, secondData.at("/records/0/quota/quotaLimit").asInt());
        assertEquals(0, secondData.at("/records/0/quota/usedCount").asInt());
        assertEquals(1010, secondData.at("/records/0/quota/restCount").asInt());
        assertEquals(0, quotas.selectCount(Wrappers.<ApplicationQuota>lambdaQuery()
                .in(ApplicationQuota::getApplicationId, applications.selectList(Wrappers.<Application>lambdaQuery()
                        .eq(Application::getOwnerUserId, owner.getId())).stream().map(Application::getId).toList())));
    }

    @Test void listQuotaMatchesDetailSnapshotAndDisabledApplicationRemainsVisible() throws Exception {
        assertEquals(200, call("GET", "/v1/utils/uuid", null).statusCode());
        app.setQuota(10); // 已有周期仍使用1000的快照。
        app.setStatus(1);
        applications.updateById(app);
        String bearer = "Bearer " + tokens.issueAccessToken(owner.getId()).value();
        var list = request("GET", "/api/application?applicationId=" + app.getId(), bearer, null);
        var detail = request("GET", "/api/application/" + app.getId() + "/quota", bearer, null);
        assertEquals(200, list.statusCode());
        assertEquals(200, detail.statusCode());
        assertEquals(json.readTree(detail.body()).at("/data"), json.readTree(list.body()).at("/data/records/0/quota"));
        assertEquals(1000, json.readTree(list.body()).at("/data/records/0/quota/quotaLimit").asInt());
        assertEquals(1, used());
    }

    private GeneratedApiKey newKey() {
        var generated = credentials.generate();
        ApiKey apiKey = new ApiKey();
        apiKey.setApplicationId(app.getId());
        apiKey.setName("key-" + UUID.randomUUID());
        apiKey.setPublicId(generated.publicId());
        apiKey.setSecretHash(generated.secretHash());
        apiKey.setKeyPreview(generated.keyPreview());
        keys.insert(apiKey);
        return generated;
    }
    private void limit(int value) { app.setQuota(value); applications.updateById(app); }
    private int used() {
        var periods = quotas.selectList(Wrappers.<ApplicationQuota>lambdaQuery().eq(ApplicationQuota::getApplicationId, app.getId()));
        return periods.stream().mapToInt(ApplicationQuota::getUsedCount).sum();
    }
    private String articleBody() { return "{\"url\":\"" + ARTICLE + "\"}"; }
    private String shortBody(OffsetDateTime expiry) throws Exception {
        return json.writeValueAsString(new CreateShortLinkRequest("demo", "https://www.douyin.com/video/1", expiry));
    }
    private void exhausted(HttpResponse<byte[]> response) throws Exception {
        assertEquals(429, response.statusCode());
        assertEquals("APPLICATION_QUOTA_EXHAUSTED", json.readTree(response.body()).at("/code").asText());
    }
    private HttpResponse<byte[]> call(String method, String path, String body) throws Exception {
        return request(method, path, "ApiKey " + key, body);
    }
    private HttpResponse<byte[]> request(String method, String path, String authorization, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (authorization != null) builder.header("Authorization", authorization);
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }
}
