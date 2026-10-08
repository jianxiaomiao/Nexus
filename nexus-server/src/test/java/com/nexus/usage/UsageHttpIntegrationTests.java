package com.nexus.usage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.credential.GeneratedApiKey;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
    @Autowired private UsageEventMapper usageEventMapper;
    @Autowired private UsageQueryService usageQueryService;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired @Qualifier("usageExecutor") private Executor usageExecutor;
    @Autowired private ObjectMapper objectMapper;

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
        if (application != null && application.getId() != null) applicationMapper.deleteById(application.getId());
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
}
