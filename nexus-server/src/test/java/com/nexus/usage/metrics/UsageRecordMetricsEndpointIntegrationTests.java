package com.nexus.usage.metrics;

import com.nexus.job.manager.ScreenshotJobScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.nexus.usage.metrics.UsageRecordMetrics.FailureReason.DUPLICATE_UNCONFIRMED;
import static com.nexus.usage.metrics.UsageRecordMetrics.FailureReason.QUEUE_REJECTED;
import static com.nexus.usage.metrics.UsageRecordMetrics.FailureReason.TRANSIENT_WRITE_EXHAUSTED;
import static com.nexus.usage.metrics.UsageRecordMetrics.FailureReason.WRITE_FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.address=127.0.0.1",
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,metrics,prometheus"
        })
class UsageRecordMetricsEndpointIntegrationTests {
    @LocalManagementPort
    int managementPort;

    @Autowired
    UsageRecordMetrics metrics;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ScreenshotJobScheduler scheduler;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void requireDedicatedTestDatabase() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)
                .startsWith("nexus_test_user@"));
    }

    @Test
    void actuatorAndPrometheusExposeAllFourFailureReasons() throws Exception {
        metrics.recordFailure(QUEUE_REJECTED);
        metrics.recordFailure(TRANSIENT_WRITE_EXHAUSTED);
        metrics.recordFailure(DUPLICATE_UNCONFIRMED);
        metrics.recordFailure(WRITE_FAILED);

        String actuator = get("/actuator/metrics/"
                + UsageRecordMetrics.FAILURE_METRIC_NAME);
        String filtered = get("/actuator/metrics/"
                + UsageRecordMetrics.FAILURE_METRIC_NAME
                + "?tag=reason%3Atransient_write_exhausted");
        String prometheus = get("/actuator/prometheus");

        assertTrue(actuator.contains("queue_rejected"));
        assertTrue(actuator.contains("transient_write_exhausted"));
        assertTrue(actuator.contains("duplicate_unconfirmed"));
        assertTrue(actuator.contains("write_failed"));
        assertTrue(filtered.contains("\"COUNT\""));

        assertTrue(prometheus.contains("nexus_usage_record_failures_total"));
        assertTrue(prometheus.contains("reason=\"queue_rejected\""));
        assertTrue(prometheus.contains("reason=\"transient_write_exhausted\""));
        assertTrue(prometheus.contains("reason=\"duplicate_unconfirmed\""));
        assertTrue(prometheus.contains("reason=\"write_failed\""));
    }

    private String get(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + managementPort + pathAndQuery))
                .GET()
                .build();
        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }
}
