package com.nexus.usage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.credential.GeneratedApiKey;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.quota.QuotaTestCleanup;
import com.nexus.quota.mapper.ApplicationQuotaMapper;
import com.nexus.screenshot.exception.ScreenshotException;
import com.nexus.screenshot.service.ScreenshotRenderer;
import com.nexus.usage.metrics.UsageRecordMetrics;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.token.JwtTokenService;
import com.nexus.usage.dto.TimeRange;
import com.nexus.usage.dto.UsageQueryRequest;
import com.nexus.usage.dto.UsageQueryResponse;
import com.nexus.usage.entity.UsageEvent;
import com.nexus.usage.exception.InvalidUsageTimeRangeException;
import com.nexus.usage.mapper.UsageEventMapper;
import com.nexus.usage.service.UsageQueryService;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(UsageHttpIntegrationTests.UnhandledUsageController.class)
class UsageHttpIntegrationTests {
    private static final String TEST_JWT_SECRET = Base64.getEncoder()
            .encodeToString(SecureRandom.getSeed(32));

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("nexus.auth.jwt.issuer", () -> "nexus");
        registry.add("nexus.auth.jwt.access-token-ttl", () -> "30m");
    }

    @Value("${local.server.port}") private int port;
    @Autowired private ApiKeyCredentialGenerator generator;
    @Autowired private UserMapper userMapper;
    @Autowired private ApplicationMapper applicationMapper;
    @Autowired private ApiKeyMapper apiKeyMapper;
    @MockitoSpyBean private UsageEventMapper usageEventMapper;
    @Autowired private UsageQueryService usageQueryService;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired @Qualifier("usageExecutor") private Executor usageExecutor;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;
    @MockitoBean private ScreenshotRenderer screenshotRenderer;

    @Autowired ApplicationQuotaMapper quotaMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private User owner;
    private Application application;
    private ApiKey key;
    private GeneratedApiKey generated;

    @BeforeEach
    void createCredential() {
        owner = new User();
        owner.setEmail("usage-test-" + UUID.randomUUID() + "@example.com");
        owner.setDisplayName("Usage integration user");
        owner.setPasswordHash("test-only-password-hash");
        userMapper.insert(owner);

        application = new Application();
        application.setOwnerUserId(owner.getId());
        application.setName("usage-test-" + UUID.randomUUID());
        applicationMapper.insert(application);

        generated = generator.generate();
        key = new ApiKey();
        key.setApplicationId(application.getId());
        key.setName("usage-test-key");
        key.setPublicId(generated.publicId());
        key.setSecretHash(generated.secretHash());
        key.setKeyPreview(generated.keyPreview());
        apiKeyMapper.insert(key);
    }

    @AfterEach
    void removeCredential() throws InterruptedException {
        if (key != null && key.getId() != null) {
            UsageTestCleanup.deleteEventsAfterPendingWrites(usageExecutor, usageEventMapper, key.getId());
            apiKeyMapper.deleteById(key.getId());
        }
        if (application != null && application.getId() != null) {
            QuotaTestCleanup.deletePeriods(quotaMapper, application.getId());
            applicationMapper.deleteById(application.getId());
        }
        if (owner != null && owner.getId() != null) userMapper.deleteById(owner.getId());
    }

    @Test
    void successfulAndInvalidHashRequestsAreRecordedWithOneEventEach() throws Exception {
        HttpResponse<String> successful = postHash("{\"algorithm\":\"SHA256\",\"value\":\"hello\"}");
        HttpResponse<String> invalid = postHash("{\"value\":\"hello\"}");

        assertEquals(200, successful.statusCode());
        assertEquals("SUCCESS", objectMapper.readTree(successful.body()).at("/code").asText());
        assertEquals(400, invalid.statusCode());
        assertEquals("VALIDATION_ERROR", objectMapper.readTree(invalid.body()).at("/code").asText());

        List<UsageEvent> events = awaitEvents(2);
        assertEquals(2, events.size());
        assertEquals(1, events.stream().filter(event -> event.getHttpStatusCode() == 200).count());
        assertEquals(1, events.stream().filter(event -> event.getHttpStatusCode() == 400).count());
        assertEquals(2, events.stream().map(UsageEvent::getEventId).distinct().count());
        for (UsageEvent event : events) {
            assertEquals(key.getId(), event.getApiKeyId());
            assertEquals(application.getId(), event.getApplicationId());
            assertEquals("utils.hash", event.getApiCode());
            assertNotNull(event.getOccurredAt());
            assertTrue(event.getDurationMs() >= 0);
            assertEquals(event.getEventId(), UUID.fromString(event.getEventId()).toString());
        }
    }

    @Test
    void handled429And503ResponsesAreRecordedWithTheirFinalStatuses() throws Exception {
        application.setQuota(0);
        applicationMapper.updateById(application);
        HttpResponse<String> exhausted = getMachine("/v1/test/usage-unhandled?mode=success");
        assertEquals(200, exhausted.statusCode());

        HttpResponse<String> quotaRejected = getMachine("/v1/utils/uuid");
        assertEquals(429, quotaRejected.statusCode());
        awaitEvents(2);

        application.setQuota(1000);
        applicationMapper.updateById(application);
        when(screenshotRenderer.render(any()))
                .thenThrow(new ScreenshotException(ScreenshotException.Kind.BUSY));
        HttpResponse<String> unavailable = postMachine(
                "/v1/web/screenshot", "{\"url\":\"https://blog.csdn.net/article\"}");
        assertEquals(503, unavailable.statusCode());

        List<UsageEvent> events = awaitEvents(3);
        assertEquals(1, events.stream()
                .filter(event -> event.getHttpStatusCode() == 200).count());
        assertEquals(1, events.stream()
                .filter(event -> event.getHttpStatusCode() == 429).count());
        assertEquals(1, events.stream()
                .filter(event -> event.getHttpStatusCode() == 503).count());
    }

    @Test
    void unhandledExceptionIsReturnedAndRecordedAs500() throws Exception {
        HttpResponse<String> response = getMachine("/v1/test/usage-unhandled");

        assertEquals(500, response.statusCode());
        List<UsageEvent> events = awaitEvents(1);
        assertEquals(1, events.size());
        assertEquals(500, events.getFirst().getHttpStatusCode());
        assertEquals("uuid.generate", events.getFirst().getApiCode());
    }

    @Test
    void usageWriteFailureDoesNotChangeSuccessfulHttpResponseOrCreateEvent()
            throws Exception {
        double failuresBefore = usageWriteFailureCount();
        doThrow(new IllegalStateException("test-only usage write failure"))
                .when(usageEventMapper).insert(any(UsageEvent.class));

        HttpResponse<String> response = getMachine("/v1/utils/uuid");

        assertEquals(200, response.statusCode());
        awaitUsageWriteFailure(failuresBefore + 1.0);
        assertEquals(failuresBefore + 1.0, usageWriteFailureCount());
        assertEquals(0L, usageEventMapper.selectCount(Wrappers.<UsageEvent>lambdaQuery()
                .eq(UsageEvent::getApiKeyId, key.getId())));
    }

    @Test
    void aggregatesBeijingDaysAndAllowsDisabledKeyHistory() throws Exception {
        insertUsage(LocalDateTime.of(2026, 10, 7, 15, 59, 59, 999_000_000), "utils.hash");
        insertUsage(LocalDateTime.of(2026, 10, 7, 16, 0), "utils.hash");
        insertUsage(LocalDateTime.of(2026, 10, 9, 16, 0), "uuid.generate");
        insertUsage(LocalDateTime.of(2026, 10, 10, 16, 0), "utils.hash");

        application.setStatus(1);
        applicationMapper.updateById(application);
        key.setStatus(1);
        apiKeyMapper.updateById(key);

        UsageQueryRequest request = new UsageQueryRequest(key.getId(), application.getId(), TimeRange.CUSTOM,
                LocalDateTime.of(2026, 10, 8, 0, 0), LocalDateTime.of(2026, 10, 11, 0, 0));
        UsageQueryResponse response = usageQueryService.queryMyUsageEvent(owner.getId(), request);

        assertEquals(4, response.allTimeCount());
        assertEquals(2, response.periodCount());
        assertEquals("Asia/Shanghai", response.timeZone());
        assertEquals(OffsetDateTime.parse("2026-10-08T00:00:00+08:00"), response.periodStart());
        assertEquals(OffsetDateTime.parse("2026-10-11T00:00:00+08:00"), response.periodEndExclusive());
        assertEquals(List.of(
                new UsageQueryResponse.DailyCount(LocalDate.of(2026, 10, 8), 1),
                new UsageQueryResponse.DailyCount(LocalDate.of(2026, 10, 9), 0),
                new UsageQueryResponse.DailyCount(LocalDate.of(2026, 10, 10), 1)
        ), response.dailyCounts());
        assertEquals(List.of(
                new UsageQueryResponse.ApiCount("utils.hash", "哈希计算工具", 1),
                new UsageQueryResponse.ApiCount("uuid.generate", "生成UUID", 1)
        ), response.apiCounts());
        assertThrows(ApplicationNotFoundException.class,
                () -> usageQueryService.queryMyUsageEvent(owner.getId() + 1, request));

        String queryUrl = "http://localhost:" + port + "/api/usage?applicationId=" + application.getId()
                + "&apiKeyId=" + key.getId()
                + "&timeRange=CUSTOM&customStartTime=2026-10-08T00:00:00"
                + "&customEndTime=2026-10-11T00:00:00";
        HttpRequest authorized = HttpRequest.newBuilder(URI.create(queryUrl))
                .header("Authorization", "Bearer " + jwtTokenService.issueAccessToken(owner.getId()).value())
                .GET().build();
        HttpResponse<String> httpResponse = httpClient.send(authorized, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, httpResponse.statusCode());
        var body = objectMapper.readTree(httpResponse.body());
        assertEquals("SUCCESS", body.at("/code").asText());
        assertEquals(4, body.at("/data/allTimeCount").asInt());
        assertEquals(2, body.at("/data/periodCount").asInt());
        assertEquals("2026-10-09", body.at("/data/dailyCounts/1/date").asText());
        assertEquals(0, body.at("/data/dailyCounts/1/count").asInt());

        HttpResponse<String> unauthenticated = httpClient.send(
                HttpRequest.newBuilder(URI.create(queryUrl)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthenticated.statusCode());
        assertEquals("INVALID_ACCESS_TOKEN", objectMapper.readTree(unauthenticated.body()).at("/code").asText());

        HttpRequest invalidRange = HttpRequest.newBuilder(URI.create(queryUrl.replace(
                        "&customStartTime=2026-10-08T00:00:00", "")))
                .header("Authorization", "Bearer " + jwtTokenService.issueAccessToken(owner.getId()).value())
                .GET().build();
        HttpResponse<String> invalidResponse = httpClient.send(invalidRange, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, invalidResponse.statusCode());
        assertEquals("INVALID_USAGE_TIME_RANGE", objectMapper.readTree(invalidResponse.body()).at("/code").asText());
    }

    @Test
    void rejectsInvalidCustomTimeRange() {
        UsageQueryRequest missingStart = new UsageQueryRequest(key.getId(), application.getId(),
                TimeRange.CUSTOM, null, LocalDateTime.of(2026, 10, 9, 0, 0));
        UsageQueryRequest reversed = new UsageQueryRequest(key.getId(), application.getId(),
                TimeRange.CUSTOM, LocalDateTime.of(2026, 10, 9, 0, 0), LocalDateTime.of(2026, 10, 8, 0, 0));
        UsageQueryRequest tooLong = new UsageQueryRequest(key.getId(), application.getId(),
                TimeRange.CUSTOM, LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 5, 1, 0, 0));

        assertThrows(InvalidUsageTimeRangeException.class,
                () -> usageQueryService.queryMyUsageEvent(owner.getId(), missingStart));
        assertThrows(InvalidUsageTimeRangeException.class,
                () -> usageQueryService.queryMyUsageEvent(owner.getId(), reversed));
        assertThrows(InvalidUsageTimeRangeException.class,
                () -> usageQueryService.queryMyUsageEvent(owner.getId(), tooLong));
    }

    @Test
    void pagesElevenEventsAndKeepsDisabledHistoryPrivate() throws Exception {
        LocalDateTime sameInstant = LocalDateTime.of(2026, 10, 8, 12, 0);
        for (int index = 1; index <= 11; index++) {
            insertUsage(sameInstant, "event." + index);
        }
        key.setStatus(1);
        apiKeyMapper.updateById(key);

        String url = "http://localhost:" + port + "/api/usage/events?applicationId=" + application.getId()
                + "&apiKeyId=" + key.getId();
        String token = jwtTokenService.issueAccessToken(owner.getId()).value();
        HttpResponse<String> first = getWithBearer(url, token);
        assertEquals(200, first.statusCode());
        var firstData = objectMapper.readTree(first.body()).path("data");
        assertEquals(11, firstData.path("total").asInt());
        assertEquals(1, firstData.path("current").asInt());
        assertEquals(10, firstData.path("size").asInt());
        assertEquals(10, firstData.path("records").size());
        assertEquals("event.11", firstData.at("/records/0/apiCode").asText());
        assertEquals("event.2", firstData.at("/records/9/apiCode").asText());
        assertEquals("2026-10-08T20:00:00+08:00", firstData.at("/records/0/occurredAt").asText());
        assertEquals(4, firstData.at("/records/0").size());

        HttpResponse<String> second = getWithBearer(url + "&current=2", token);
        assertEquals(200, second.statusCode());
        assertEquals(1, objectMapper.readTree(second.body()).at("/data/records").size());
        assertEquals("event.1", objectMapper.readTree(second.body()).at("/data/records/0/apiCode").asText());

        HttpResponse<String> custom = getWithBearer(url + "&size=5&current=3", token);
        assertEquals(1, objectMapper.readTree(custom.body()).at("/data/records").size());

        HttpResponse<String> invalid = getWithBearer(url + "&size=101", token);
        assertEquals(400, invalid.statusCode());
        assertEquals("INVALID_USAGE_PAGINATION", objectMapper.readTree(invalid.body()).at("/code").asText());
        assertEquals(400, getWithBearer(url + "&current=0", token).statusCode());

        HttpResponse<String> wrongApplication = getWithBearer(url.replace(
                "applicationId=" + application.getId(), "applicationId=" + (application.getId() + 1000000)), token);
        assertEquals(404, wrongApplication.statusCode());
        assertEquals("APPLICATION_NOT_FOUND", objectMapper.readTree(wrongApplication.body()).at("/code").asText());

        HttpResponse<String> anonymous = httpClient.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());
    }

    private HttpResponse<String> getWithBearer(String url, String token) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void insertUsage(LocalDateTime occurredAt, String apiCode) {
        UsageEvent event = new UsageEvent();
        event.setApiKeyId(key.getId());
        event.setApplicationId(application.getId());
        event.setApiCode(apiCode);
        event.setHttpStatusCode(200);
        event.setDurationMs(1);
        event.setOccurredAt(occurredAt);
        event.setEventId(UUID.randomUUID().toString());
        usageEventMapper.insert(event);
    }

    private HttpResponse<String> postHash(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/utils/hash"))
                .header("Authorization", "ApiKey " + generated.plaintextKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getMachine(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .header("Authorization", "ApiKey " + generated.plaintextKey())
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postMachine(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .header("Authorization", "ApiKey " + generated.plaintextKey())
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private double usageWriteFailureCount() {
        return meterRegistry.get(UsageRecordMetrics.FAILURE_METRIC_NAME)
                .tag("reason", UsageRecordMetrics.FailureReason.WRITE_FAILED.tagValue())
                .counter()
                .count();
    }

    private void awaitUsageWriteFailure(double expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (usageWriteFailureCount() < expected) {
            if (System.nanoTime() >= deadline) {
                assertEquals(expected, usageWriteFailureCount());
            }
            Thread.sleep(25);
        }
    }

    private List<UsageEvent> awaitEvents(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        List<UsageEvent> events;
        do {
            events = usageEventMapper.selectList(Wrappers.<UsageEvent>lambdaQuery()
                    .eq(UsageEvent::getApiKeyId, key.getId()));
            if (events.size() >= expected) return events;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return events;
    }

    @RestController
    static class UnhandledUsageController {
        @com.nexus.usage.web.UsageApi(ApiCode.UUID_GENERATE)
        @GetMapping("/v1/test/usage-unhandled")
        String invoke(jakarta.servlet.http.HttpServletRequest request) {
            if ("success".equals(request.getParameter("mode"))) {
                return "ok";
            }
            throw new IllegalStateException("test-only unhandled failure");
        }
    }
}
