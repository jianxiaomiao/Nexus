package com.nexus.observability;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyAuthenticator;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.job.manager.ScreenshotJobScheduler;
import com.nexus.openapi.ratelimit.ApplicationRateLimiter;
import com.nexus.openapi.ratelimit.RateLimitAdmission;
import com.nexus.quota.service.ApplicationQuotaService;
import com.nexus.usage.service.UsageRecordService;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.address=127.0.0.1",
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,metrics,prometheus"
        })
class FrameworkHttpMetricsIntegrationTests {
    private static final String TEST_CREDENTIAL = "nx_test-secret-http-metric";
    private static final String SECRET_JOB_ID =
            "123e4567-e89b-12d3-a456-426614174999";

    @LocalServerPort
    int serverPort;

    @LocalManagementPort
    int managementPort;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ApiKeyAuthenticator authenticator;

    @MockitoBean
    ApplicationRateLimiter rateLimiter;

    @MockitoBean
    ApplicationQuotaService quotaService;

    @MockitoBean
    UsageRecordService usageRecordService;

    @MockitoBean
    ScreenshotJobScheduler screenshotJobScheduler;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void arrangeControlledFilterDecisions() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)
                .startsWith("nexus_test_user@"));

        when(authenticator.authenticate(TEST_CREDENTIAL))
                .thenReturn(new ApiKeyIdentity(7L, 42L));
        when(rateLimiter.acquire(42L)).thenReturn(
                new RateLimitAdmission.Allowed(),
                new RateLimitAdmission.Allowed(),
                new RateLimitAdmission.Rejected(
                        RateLimitAdmission.RejectReason.APPLICATION_QUEUE_FULL));
    }

    @Test
    void recordsControllerAndEarlyFilterResponsesWithoutRawSensitiveUris()
            throws Exception {
        assertEquals(200, request("GET", "/v1/utils/uuid", null, true).statusCode());
        assertEquals(400, request("POST", "/v1/utils/hash",
                "{\"algorithm\":\"SHA256\"}", true).statusCode());
        assertEquals(401, request("GET",
                "/v1/web/screenshot-jobs/" + SECRET_JOB_ID
                        + "?credential=must-not-be-a-tag",
                null, false).statusCode());
        assertEquals(429, request("GET",
                "/v1/web/screenshot-jobs/" + SECRET_JOB_ID,
                null, true).statusCode());

        assertEquals(1.0, countForStatus("200"));
        assertEquals(1.0, countForStatus("400"));
        assertEquals(1.0, countForStatus("401"));
        assertEquals(1.0, countForStatus("429"));

        List<Meter.Id> requestMeters = meterRegistry.get("http.server.requests")
                .meters().stream().map(Meter::getId).toList();
        List<String> uriTags = requestMeters.stream()
                .map(id -> id.getTag("uri"))
                .toList();

        assertTrue(uriTags.contains("/v1/utils/uuid"));
        assertTrue(uriTags.contains("/v1/utils/hash"));
        assertEquals(List.of("UNKNOWN"), uriTagsForStatus("401"));
        assertEquals(List.of("UNKNOWN"), uriTagsForStatus("429"));
        assertTrue(uriTags.stream().noneMatch(uri -> uri.contains(SECRET_JOB_ID)));
        assertTrue(uriTags.stream().noneMatch(uri -> uri.contains("credential")));
        assertTrue(uriTags.stream().noneMatch(uri -> uri.contains(TEST_CREDENTIAL)));
        assertTrue(uriTags.stream().noneMatch(uri -> uri.contains("?")));
        assertTrue(requestMeters.stream().allMatch(id -> {
            String method = id.getTag("method");
            String status = id.getTag("status");
            String outcome = id.getTag("outcome");
            return method != null && status != null && outcome != null;
        }));

        String actuator = managementGet("/actuator/metrics/http.server.requests");
        String prometheus = managementGet("/actuator/prometheus");
        assertTrue(actuator.contains("\"COUNT\""));
        assertFalse(actuator.contains(SECRET_JOB_ID));
        assertFalse(actuator.contains(TEST_CREDENTIAL));
        assertTrue(prometheus.contains("http_server_requests_seconds_count"));
        assertFalse(prometheus.contains(SECRET_JOB_ID));
        assertFalse(prometheus.contains(TEST_CREDENTIAL));
    }

    private double countForStatus(String status) {
        return meterRegistry.find("http.server.requests")
                .tag("status", status)
                .timers().stream()
                .mapToDouble(Timer::count)
                .sum();
    }

    private List<String> uriTagsForStatus(String status) {
        return meterRegistry.find("http.server.requests")
                .tag("status", status)
                .meters().stream()
                .map(meter -> meter.getId().getTag("uri"))
                .distinct()
                .toList();
    }

    private HttpResponse<String> request(
            String method, String path, String body, boolean authenticated)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + serverPort + path));
        if (authenticated) {
            request.header("Authorization", "ApiKey " + TEST_CREDENTIAL);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String managementGet(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + managementPort + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }
}
