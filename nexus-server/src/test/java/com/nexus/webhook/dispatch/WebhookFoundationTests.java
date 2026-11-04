package com.nexus.webhook.dispatch;

import com.nexus.webhook.entity.WebhookOutbox;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@JsonTest(properties = {"spring.datasource.url=jdbc:mysql://127.0.0.1:1/nexus_test", "spring.flyway.enabled=false"})
class WebhookFoundationTests {
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationContext context;
    @Test void urlSyntaxRejectsUnsafeAndNumericHosts() {
        WebhookUrlPolicy policy = new WebhookUrlPolicy();
        assertEquals("example.com", policy.requireAllowedUrl("https://example.com:8443/a?q=x").getHost());
        for (String value : new String[]{"http://example.com", "https://u:p@example.com", "https://example.com/#x",
                "https://example.com:0", "https://example.com:65536", "https://example.com:", "https://127.0.0.1",
                "https://2130706433", "https://0x7f000001", "https://[::1]", "https://a..com", "https://bad_host"})
            assertThrows(IllegalArgumentException.class, () -> policy.requireAllowedUrl(value), value);
        var error = assertThrows(IllegalArgumentException.class, () -> policy.requireAllowedUrl("https://user:password@bad host/?secret=x"));
        assertNull(error.getCause());
        assertFalse(error.toString().contains("password"));
    }

    @Test void allResolvedAddressesMustBePublic() throws Exception {
        InetAddress publicIp = InetAddress.getByName("8.8.8.8");
        assertEquals(1, WebhookUrlPolicy.checked("example.com", h -> new InetAddress[]{publicIp}).length);
        for (String blocked : new String[]{"127.0.0.1", "10.0.0.1", "169.254.1.1", "100.64.0.1",
                "2001::1", "2001:db8::1", "2002::1", "3fff::1", "::1", "fc00::1"}) {
            InetAddress ip = InetAddress.getByName(blocked);
            assertThrows(WebhookDisallowedAddressException.class, () -> WebhookUrlPolicy.checked("example.com", h -> new InetAddress[]{publicIp, ip}), blocked);
        }
        assertThrows(UnknownHostException.class, () -> WebhookUrlPolicy.checked("example.com", h -> new InetAddress[0]));
        assertTrue(WebhookUrlPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test void payloadIsExplicitUtf8JsonWithUtcAndNulls() {
        assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty());
        assertTrue(context.getBeansOfType(org.flywaydb.core.Flyway.class).isEmpty());
        WebhookOutbox event = new WebhookOutbox();
        event.setEventId("中文\"\\\n\t"); event.setEventType("done"); event.setApplicationId(17L); event.setJobId("任务");
        event.setCompletedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5)); event.setResultSizeBytes(123L); event.setResultSha256("abc");
        event.setClaimToken("DO_NOT_EXPOSE"); event.setDeliveryUrl("https://secret.example/");
        byte[] bytes = WebhookPayload.from(event).toBytes(mapper);
        var json = mapper.readTree(bytes);
        assertEquals("中文\"\\\n\t", json.get("eventId").asText());
        assertEquals("2026-01-02T03:04:05Z", json.get("completedAt").asText());
        assertEquals(123L, json.get("result").get("sizeBytes").asLong());
        assertTrue(json.get("resultExpiresAt").isNull()); assertTrue(json.get("failureCode").isNull());
        assertEquals(8, json.size()); assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("DO_NOT_EXPOSE"));
        event.setResultSizeBytes(null); event.setResultSha256(null); event.setFailureCode("失败");
        json = mapper.readTree(WebhookPayload.from(event).toBytes(mapper));
        assertTrue(json.get("result").isNull()); assertEquals("失败", json.get("failureCode").asText());
    }

    @Test void bridgeCancelsPreAndPostBoundFuturesAndRejectsSecondBinding() {
        CancellationToken token = new CancellationToken();
        HttpCancellationBridge bridge = new HttpCancellationBridge(token);
        AtomicInteger cancellations = new AtomicInteger();
        FutureTask<Void> one = new FutureTask<Void>(() -> null) {
            @Override public boolean cancel(boolean interrupt) { cancellations.incrementAndGet(); return super.cancel(interrupt); }
        };
        FutureTask<Void> two = new FutureTask<>(() -> null);
        bridge.bind(one); assertThrows(IllegalStateException.class, () -> bridge.bind(two));
        token.cancel(); token.cancel(); assertTrue(one.isCancelled()); assertEquals(1, cancellations.get());
        bridge.bind(two); assertTrue(two.isCancelled());
        CancellationToken cancelled = new CancellationToken(); cancelled.cancel();
        FutureTask<Void> later = new FutureTask<>(() -> null); new HttpCancellationBridge(cancelled).bind(later);
        assertTrue(later.isCancelled());
    }

    @Test void bindCancelRaceNeverLosesCancellation() throws Exception {
        for (int i = 0; i < 300; i++) {
            CancellationToken token = new CancellationToken(); HttpCancellationBridge bridge = new HttpCancellationBridge(token);
            FutureTask<Void> future = new FutureTask<>(() -> null); CountDownLatch start = new CountDownLatch(1);
            Thread t = new Thread(() -> { try { start.await(); bridge.bind(future); } catch (InterruptedException e) { throw new AssertionError(e); } });
            t.start(); start.countDown(); token.cancel(); t.join(5000); assertFalse(t.isAlive()); assertTrue(future.isCancelled());
        }
    }

    @Test void throwingFutureDoesNotStopOtherCancellationCallbacks() {
        CancellationToken token = new CancellationToken(); HttpCancellationBridge bridge = new HttpCancellationBridge(token);
        bridge.bind(new FutureTask<Void>(() -> null) { @Override public boolean cancel(boolean interrupt) { throw new IllegalStateException(); } });
        AtomicInteger called = new AtomicInteger(); token.onCancel(called::incrementAndGet); token.cancel();
        assertEquals(1, called.get());
    }
}
