package com.nexus.webhook.dispatch;

import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.manager.ScreenshotJobManager;
import com.nexus.job.manager.ScreenshotJobScheduler;
import com.nexus.job.mapper.ScreenshotJobMapper;
import com.nexus.screenshot.exception.ScreenshotException;
import com.nexus.screenshot.service.ScreenshotRenderer;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import com.nexus.webhook.crypto.*;
import com.nexus.webhook.entity.*;
import com.nexus.webhook.mapper.*;
import com.nexus.webhook.dto.CreateWebhookRequest;
import com.nexus.webhook.service.ApplicationWebhookService;
import com.nexus.webhook.service.WebhookEndpointValidator;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import javax.net.ssl.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "nexus.webhook.scheduling.enabled=false")
class WebhookProductionFlowIntegrationTests {
    @TempDir Path temp;
    @MockitoBean ScreenshotJobScheduler screenshotScheduler;
    @MockitoBean ScreenshotRenderer renderer;
    @MockitoBean WebhookEndpointValidator endpointValidator;
    @Autowired ApplicationWebhookService webhookManagement;
    @Autowired ScreenshotJobManager manager;
    @Autowired ScreenshotJobMapper jobs;
    @Autowired WebhookOutboxMapper outbox;
    @Autowired ApplicationWebhookMapper configs;
    @Autowired WebhookSecretMapper secrets;
    @Autowired UserMapper users;
    @Autowired ApplicationMapper applications;
    @Autowired ApiKeyMapper keys;
    @Autowired ApiKeyCredentialGenerator credentials;
    @Autowired WebhookSecretCrypto crypto;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    User user; Application app; ApiKey key; ApplicationWebhook config;
    @BeforeEach void setup() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("nexus_test_user@"));
        user = new User(); user.setEmail(UUID.randomUUID()+"@example.test"); user.setDisplayName("webhook flow"); user.setPasswordHash("test-placeholder"); users.insert(user);
        app = new Application(); app.setOwnerUserId(user.getId()); app.setName("flow-"+UUID.randomUUID()); applications.insert(app);
        var generated = credentials.generate(); key = new ApiKey(); key.setApplicationId(app.getId()); key.setName("flow"); key.setPublicId(generated.publicId()); key.setSecretHash(generated.secretHash()); key.setKeyPreview(generated.keyPreview()); keys.insert(key);
        when(endpointValidator.validate(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    }
    @AfterEach void cleanup() {
        if (app != null && app.getId() != null) {
            jdbc.update("DELETE FROM webhook_outbox WHERE application_id = ?", app.getId());
            if (config != null && config.getId() != null) { jdbc.update("DELETE FROM webhook_secrets WHERE webhook_id = ?", config.getId()); configs.deleteById(config.getId()); }
            jdbc.update("DELETE FROM screenshot_jobs WHERE api_key_id = ?", key.getId()); keys.deleteById(key.getId()); applications.deleteById(app.getId());
        }
        if (user != null && user.getId() != null) users.deleteById(user.getId());
    }
    @Test void competingRecoveryAndDispatchersClaimEachEventOnce() throws Exception {
        var rows = new java.util.ArrayList<WebhookOutbox>();
        for (int i = 0; i < 2; i++) {
            var row = new WebhookOutbox(); row.setEventId(UUID.randomUUID().toString()); row.setJobId(UUID.randomUUID().toString());
            row.setApplicationId(app.getId()); row.setEventType("screenshot.completed"); row.setEventStatus(1);
            row.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC)); row.setQueryUrl("/test"); row.setStatus(0);
            row.setAttemptCount(0L); row.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)); outbox.insert(row);
            assertEquals(1, outbox.claim(row.getId(), UUID.randomUUID().toString(), 60_000_000L)); rows.add(row);
        }
        jdbc.update("UPDATE webhook_outbox SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE application_id = ?", app.getId());
        var callers = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var recovery1 = new WebhookLeaseRecovery(outbox, transactions, 1); var recovery2 = new WebhookLeaseRecovery(outbox, transactions, 1);
            var a = callers.submit(() -> { start.await(); recovery1.recoverExpired(); return null; });
            var b = callers.submit(() -> { start.await(); recovery2.recoverExpired(); return null; }); start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
            // If one instance found a locked row, the next tick must drain it.
            recovery1.recoverExpired();
            assertEquals(0, outbox.selectById(rows.get(0).getId()).getStatus()); assertEquals(0, outbox.selectById(rows.get(1).getId()).getStatus());
            var seen = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet(); var delivered = new CountDownLatch(2);
            WebhookWorker worker = event -> { seen.add(event.event().getId()); delivered.countDown(); };
            var exec1 = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.AbortPolicy());
            var exec2 = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.AbortPolicy());
            try {
                var d1 = new WebhookDispatcher(outbox,new TransactionTemplate(transactions),exec1,new Semaphore(1),Clock.systemUTC(),Duration.ofSeconds(30),worker);
                var d2 = new WebhookDispatcher(outbox,new TransactionTemplate(transactions),exec2,new Semaphore(1),Clock.systemUTC(),Duration.ofSeconds(30),worker);
                var gate = new CountDownLatch(1);
                var f1 = callers.submit(() -> { gate.await(); d1.dispatchDueEvents(); return null; });
                var f2 = callers.submit(() -> { gate.await(); d2.dispatchDueEvents(); return null; }); gate.countDown(); f1.get(10,TimeUnit.SECONDS); f2.get(10,TimeUnit.SECONDS);
                assertTrue(delivered.await(10,TimeUnit.SECONDS)); assertEquals(java.util.Set.of(rows.get(0).getId(), rows.get(1).getId()), seen);
                assertNotEquals(outbox.selectById(rows.get(0).getId()).getClaimToken(), outbox.selectById(rows.get(1).getId()).getClaimToken());
            } finally { exec1.shutdownNow(); exec2.shutdownNow(); assertTrue(exec1.awaitTermination(10,TimeUnit.SECONDS)); assertTrue(exec2.awaitTermination(10,TimeUnit.SECONDS)); }
        } finally { callers.shutdownNow(); assertTrue(callers.awaitTermination(10,TimeUnit.SECONDS)); }
    }
    @Test void recoveryBatchRollsBackAfterSecondUpdateFails() {
        var rows = new java.util.ArrayList<WebhookOutbox>();
        for (int i = 0; i < 2; i++) {
            var row = new WebhookOutbox(); row.setEventId(UUID.randomUUID().toString()); row.setJobId(UUID.randomUUID().toString());
            row.setApplicationId(app.getId()); row.setEventType("screenshot.completed"); row.setEventStatus(1);
            row.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC)); row.setQueryUrl("/test"); row.setStatus(0);
            row.setAttemptCount(0L); row.setNextAttemptAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)); outbox.insert(row);
            assertEquals(1, outbox.claim(row.getId(), "rollback-" + i, 60_000_000L)); rows.add(row);
        }
        jdbc.update("UPDATE webhook_outbox SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE application_id = ?", app.getId());
        var before = rows.stream().map(r -> outbox.selectById(r.getId())).toList();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var failing = mock(WebhookOutboxMapper.class, org.mockito.AdditionalAnswers.delegatesTo(outbox));
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException("injected failure");
            return outbox.recoverExpired(inv.getArgument(0), inv.getArgument(1));
        }).when(failing).recoverExpired(anyLong(), anyString());
        assertThrows(IllegalStateException.class, () -> new WebhookLeaseRecovery(failing, transactions, 10).recoverExpired());
        assertEquals(2, calls.get());
        for (var original : before) {
            var after = outbox.selectById(original.getId());
            assertEquals(original.getStatus(), after.getStatus());
            assertEquals(original.getClaimToken(), after.getClaimToken());
            assertEquals(original.getLeaseExpiresAt(), after.getLeaseExpiresAt());
        }
    }
    @Test void terminalEventSurvivesRestartAndIsDeliveredOverVerifiedHttps() throws Exception {
        Path store = temp.resolve("server.p12"); Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe");
        if (!Files.exists(keytool)) keytool = keytool.resolveSibling("keytool");
        Process generator = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "testpass", "-keypass", "testpass", "-dname", "CN=webhook.invalid", "-ext", "SAN=dns:webhook.invalid", "-validity", "1", "-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(generator.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, generator.exitValue());
        KeyStore keyStore = KeyStore.getInstance("PKCS12"); try (var input = Files.newInputStream(store)) { keyStore.load(input, "testpass".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keyStore, "testpass".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keyStore);
        SSLContext serverTls = SSLContext.getInstance("TLS"); serverTls.init(km.getKeyManagers(), null, null);
        SSLContext clientTls = SSLContext.getInstance("TLS"); clientTls.init(null, tm.getTrustManagers(), null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0); server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        var requests = new java.util.concurrent.atomic.AtomicInteger(); var firstBody = new AtomicReference<byte[]>(); var body = new AtomicReference<byte[]>(); var timestamp = new AtomicReference<String>(); var signature = new AtomicReference<String>(); var received = new CountDownLatch(1);
        server.createContext("/hook", exchange -> { body.set(exchange.getRequestBody().readAllBytes()); int number = requests.incrementAndGet(); if (number == 1) firstBody.set(body.get()); timestamp.set(exchange.getRequestHeaders().getFirst("X-Nexus-Timestamp")); signature.set(exchange.getRequestHeaders().getFirst("X-Nexus-Signature")); exchange.sendResponseHeaders(number == 1 ? 503 : 200, -1); exchange.close(); if (number == 2) received.countDown(); }); server.start();
        try {
            var createdWebhook = webhookManagement.createMine(user.getId(), app.getId(),
                    new CreateWebhookRequest("https://webhook.invalid:"+server.getAddress().getPort()+"/hook"));
            config = configs.selectByApplicationId(app.getId());
            assertEquals(createdWebhook.id(), config.getId());
            byte[] raw = java.util.Base64.getUrlDecoder().decode(createdWebhook.secret());
            var now = LocalDateTime.now(ZoneOffset.UTC); var job = new ScreenshotJob(); job.setJobId(UUID.randomUUID().toString()); job.setApiKeyId(key.getId()); job.setTargetUrl("https://blog.csdn.net/article"); job.setStatus(1); job.setAttemptCount(1); job.setRunToken(UUID.randomUUID().toString()); job.setCreatedAt(now.minusMinutes(1)); job.setUpdatedAt(now.minusMinutes(1)); job.setStartedAt(now.minusSeconds(20)); job.setLeaseExpiresAt(now.plusMinutes(1)); jobs.insert(job);
            when(renderer.render(any())).thenThrow(new ScreenshotException(ScreenshotException.Kind.TIMEOUT));
            var execute = ScreenshotJobManager.class.getDeclaredMethod("executeClaimedJob", ScreenshotJob.class); execute.setAccessible(true); assertEquals(false, execute.invoke(manager, job));
            var event = outbox.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<WebhookOutbox>lambdaQuery().eq(WebhookOutbox::getJobId, job.getJobId())); assertNotNull(event);
            var future = new WebhookOutbox(); future.setEventId(UUID.randomUUID().toString()); future.setJobId(UUID.randomUUID().toString());
            future.setEventType("screenshot.completed"); future.setApplicationId(app.getId()); future.setEventStatus(1);
            future.setCompletedAt(now); future.setQueryUrl("/test-future"); future.setStatus(0); future.setAttemptCount(0L); future.setNextAttemptAt(now.plusDays(1)); outbox.insert(future);
            assertEquals(1, outbox.claim(event.getId(), "abandoned-token", 60_000_000L));
            jdbc.update("UPDATE webhook_outbox SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", event.getId());
            var leaseScheduler = new LeaseScheduler();
            var leaseManager = new WebhookLeaseManager(outbox, leaseScheduler, Duration.ofSeconds(30), Duration.ofSeconds(10), new TransactionTemplate(transactions), System::nanoTime);
            var http = new WebhookHttpClient(host -> new InetAddress[]{InetAddress.getLoopbackAddress()}, clientTls, true);
            var handler = new WebhookResponseHandler(new WebhookRetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(5)), Clock.systemUTC(), bound -> 0);
            var worker = new WebhookWorkerImpl(leaseManager, new WebhookAttemptPreparer(outbox, new TransactionTemplate(transactions), 1), outbox, configs, secrets, http, crypto, new WebhookSignatureService(), json, handler, new WebhookExceptionClassifier(), Clock.systemUTC());
            var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
            var dispatcher = new WebhookDispatcher(outbox, new TransactionTemplate(transactions), executor, new Semaphore(1), Clock.systemUTC(), Duration.ofSeconds(30), worker);
            var recovery = new WebhookLeaseRecovery(outbox, transactions, 10);
            var scheduler = new WebhookScheduler(dispatcher, recovery, worker, executor, true, "20ms", "20ms");
            try {
                scheduler.start(); assertTrue(received.await(10, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (outbox.selectById(event.getId()).getStatus() != 2 && System.nanoTime() < deadline) Thread.sleep(20);
                assertEquals(2, outbox.selectById(event.getId()).getStatus()); assertEquals(2, requests.get()); assertArrayEquals(firstBody.get(), body.get()); assertEquals(0, outbox.selectById(future.getId()).getStatus()); assertEquals(0L, outbox.selectById(future.getId()).getAttemptCount());
                assertEquals(new WebhookSignatureService().sign(raw, Long.parseLong(timestamp.get()), body.get()), signature.get());
                assertEquals(event.getEventId(), json.readTree(firstBody.get()).get("eventId").asText());
                assertEquals(event.getEventId(), json.readTree(body.get()).get("eventId").asText());
                byte[] tampered = new String(body.get(), java.nio.charset.StandardCharsets.UTF_8).replace(event.getEventId(), UUID.randomUUID().toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                assertNotEquals(signature.get(), new WebhookSignatureService().sign(raw, Long.parseLong(timestamp.get()), tampered));
                assertEquals(0, outbox.markDelivered(event.getId(), "abandoned-token"));
            } finally { scheduler.stop(); leaseManager.close(); leaseScheduler.close(); http.close(); }
        } finally { server.stop(0); }
    }
}
