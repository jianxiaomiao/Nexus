package com.nexus.screenshot.metrics;

import com.nexus.job.manager.ScreenshotJobScheduler;
import com.nexus.job.metrics.ScreenshotJobMetrics;
import com.nexus.screenshot.service.ScreenshotExecutionGate;
import io.micrometer.core.instrument.Timer;
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
import java.time.LocalDateTime;

import static com.nexus.screenshot.metrics.ScreenshotMetrics.ExecutionMode.SYNC;
import static com.nexus.screenshot.metrics.ScreenshotMetrics.ExecutionOutcome.SUCCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.address=127.0.0.1",
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,metrics,prometheus"
        })
class ScreenshotMetricsEndpointIntegrationTests {
    @LocalManagementPort
    int managementPort;

    @Autowired
    ScreenshotMetrics metrics;

    @Autowired
    ScreenshotExecutionGate executionGate;

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
    void actuatorAndPrometheusExposeTheSameFourScreenshotMeters() throws Exception {
        LocalDateTime startedAt = LocalDateTime.of(
                2026, 10, 24, 8, 0, 0, 250_000_000);
        metrics.recordQueueDelay(startedAt.minusNanos(250_000_000), startedAt);
        Timer.Sample execution = metrics.startExecution();
        metrics.stopExecution(execution, SYNC, SUCCEEDED);

        String queue = get("/actuator/metrics/" + ScreenshotMetrics.QUEUE_DELAY_METRIC_NAME);
        String executionMetric = get("/actuator/metrics/" + ScreenshotMetrics.EXECUTION_METRIC_NAME
                + "?tag=mode%3Async&tag=outcome%3Asucceeded");
        String unfinished = get("/actuator/metrics/"
                + ScreenshotJobMetrics.UNFINISHED_JOBS_METRIC_NAME);
        String busy = get("/actuator/metrics/"
                + ScreenshotExecutionGateMetrics.SLOT_BUSY_METRIC_NAME);
        String prometheus = get("/actuator/prometheus");

        assertTrue(queue.contains("\"COUNT\""));
        assertTrue(queue.contains("\"TOTAL_TIME\""));
        assertTrue(executionMetric.contains("\"COUNT\""));
        assertTrue(executionMetric.contains("\"TOTAL_TIME\""));
        assertTrue(unfinished.contains("\"VALUE\""));
        assertTrue(busy.contains("\"VALUE\""));
        assertEquals(0, executionGate.currentBusySlotCount());

        assertTrue(prometheus.contains("nexus_screenshot_queue_delay_seconds_count"));
        assertTrue(prometheus.contains("nexus_screenshot_execution_seconds_count"));
        assertTrue(prometheus.contains("mode=\"sync\""));
        assertTrue(prometheus.contains("outcome=\"succeeded\""));
        assertTrue(prometheus.contains("nexus_screenshot_jobs_unfinished"));
        assertTrue(prometheus.contains("nexus_screenshot_slot_busy"));
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
