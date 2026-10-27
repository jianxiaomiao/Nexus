package com.nexus.observability;

import com.nexus.job.manager.ScreenshotJobScheduler;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.address=127.0.0.1",
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,metrics,prometheus"
        })
class RuntimeMetricsIntegrationTests {
    @LocalManagementPort
    int managementPort;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    ApplicationContext applicationContext;

    @MockitoBean
    ScreenshotJobScheduler screenshotJobScheduler;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void runtimeMetersExistWithSaneUnitsAndRangesWithoutForcingGc() throws Exception {
        Collection<Gauge> memoryUsed = meterRegistry.find("jvm.memory.used").gauges();
        assertFalse(memoryUsed.isEmpty());
        assertTrue(memoryUsed.stream().allMatch(gauge -> gauge.value() >= 0.0));
        assertTrue(memoryUsed.stream().allMatch(gauge ->
                gauge.getId().getTag("area") != null
                        && gauge.getId().getTag("id") != null));

        assertTrue(gauge("jvm.threads.live").value() >= 1.0);
        assertUnitIntervalWhenAvailable(gauge("process.cpu.usage").value());
        assertUnitIntervalWhenAvailable(gauge("system.cpu.usage").value());

        TimeGauge uptime = meterRegistry.find("process.uptime").timeGauge();
        assertNotNull(uptime);
        assertTrue(uptime.value(TimeUnit.SECONDS) > 0.0);

        double diskFree = gauge("disk.free").value();
        double diskTotal = gauge("disk.total").value();
        assertTrue(diskFree >= 0.0);
        assertTrue(diskTotal > 0.0);
        assertTrue(diskFree <= diskTotal);

        assertFalse(applicationContext.getBeansOfType(JvmGcMetrics.class).isEmpty());
        List<Meter> gcMeters = meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("jvm.gc."))
                .toList();
        assertTrue(gcMeters.stream().flatMap(meter -> StreamSupport.stream(
                        meter.measure().spliterator(), false))
                .allMatch(measurement -> Double.isNaN(measurement.getValue())
                        || measurement.getValue() >= 0.0));

        Timer gcPause = meterRegistry.find("jvm.gc.pause").timer();
        if (gcPause != null) {
            assertTrue(gcPause.count() >= 0L);
            assertTrue(gcPause.totalTime(TimeUnit.SECONDS) >= 0.0);
            assertTrue(gcPause.max(TimeUnit.SECONDS) >= 0.0);
        }

        String uptimeJson = managementGet("/actuator/metrics/process.uptime");
        String prometheus = managementGet("/actuator/prometheus");
        assertTrue(uptimeJson.contains("\"baseUnit\":\"seconds\""));
        assertTrue(prometheus.contains("process_uptime_seconds"));
        assertTrue(prometheus.contains("jvm_memory_used_bytes"));
        assertTrue(prometheus.contains("jvm_threads_live_threads"));
        assertTrue(prometheus.contains("disk_free_bytes"));
        if (gcPause != null) {
            String gcPauseJson = managementGet("/actuator/metrics/jvm.gc.pause");
            assertTrue(gcPauseJson.contains("\"baseUnit\":\"seconds\""));
            assertTrue(gcPauseJson.contains("\"COUNT\""));
            assertTrue(prometheus.contains("jvm_gc_pause_seconds"));
        }
    }

    private Gauge gauge(String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        assertNotNull(gauge, () -> "missing gauge " + name);
        return gauge;
    }

    private static void assertUnitIntervalWhenAvailable(double value) {
        assertTrue(Double.isNaN(value) || value >= 0.0 && value <= 1.0);
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
