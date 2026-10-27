package com.nexus.observability;

import com.nexus.job.manager.ScreenshotJobScheduler;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.address=127.0.0.1",
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,metrics,prometheus",
                "spring.datasource.hikari.maximum-pool-size=2",
                "spring.datasource.hikari.minimum-idle=1",
                "spring.datasource.hikari.connection-timeout=5000"
        })
class HikariMetricsIntegrationTests {
    @LocalManagementPort
    int managementPort;

    @Autowired
    DataSource dataSource;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ScreenshotJobScheduler screenshotJobScheduler;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void requireDedicatedTwoConnectionPool() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class)
                .startsWith("nexus_test_user@"));
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertEquals(2, hikari.getMaximumPoolSize());
        assertEquals(1, hikari.getMinimumIdle());
    }

    @Test
    void gaugesAndAcquireTimerFollowAControlledPoolSaturation() throws Exception {
        awaitGauge("hikaricp.connections.active", 0.0);
        Timer acquire = meterRegistry.find("hikaricp.connections.acquire").timer();
        assertNotNull(acquire);
        long acquireCountBefore = acquire.count();

        String actuator = managementGet(
                "/actuator/metrics/hikaricp.connections.pending");
        String prometheus = managementGet("/actuator/prometheus");
        assertTrue(actuator.contains("\"VALUE\""));
        assertTrue(prometheus.contains("hikaricp_connections_active"));
        assertTrue(prometheus.contains("hikaricp_connections_pending"));

        Connection first = null;
        Connection second = null;
        Connection third = null;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            try {
                first = dataSource.getConnection();
                awaitGauge("hikaricp.connections.active", 1.0);
                assertEquals(1.0, gaugeValue("jdbc.connections.active"));

                second = dataSource.getConnection();
                awaitGauge("hikaricp.connections.active", 2.0);
                awaitGauge("hikaricp.connections.idle", 0.0);
                assertEquals(2.0, gaugeValue("jdbc.connections.active"));
                assertEquals(2.0, gaugeValue("hikaricp.connections.max"));

                Future<Connection> waiting = executor.submit(
                        (Callable<Connection>) dataSource::getConnection);
                awaitGauge("hikaricp.connections.pending", 1.0);

                first.close();
                first = null;
                third = waiting.get(2, TimeUnit.SECONDS);
                awaitGauge("hikaricp.connections.pending", 0.0);
                awaitGauge("hikaricp.connections.active", 2.0);
            } finally {
                if (third != null) third.close();
                if (second != null) second.close();
                if (first != null) first.close();
            }
        }

        awaitGauge("hikaricp.connections.active", 0.0);
        assertTrue(acquire.count() >= acquireCountBefore + 3);
        assertTrue(acquire.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) >= 0.0);
    }

    private double gaugeValue(String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        assertNotNull(gauge, () -> "missing gauge " + name);
        return gauge.value();
    }

    private void awaitGauge(String name, double expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (gaugeValue(name) == expected) return;
            Thread.sleep(25);
        }
        assertEquals(expected, gaugeValue(name));
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
