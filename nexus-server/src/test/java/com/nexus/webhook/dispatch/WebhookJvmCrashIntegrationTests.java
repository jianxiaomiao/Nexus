package com.nexus.webhook.dispatch;

import com.nexus.webhook.crypto.WebhookCryptoProperties;
import com.nexus.webhook.crypto.WebhookSecretCrypto;
import com.nexus.webhook.crypto.WebhookSignatureService;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import javax.net.ssl.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises OS termination, lease expiry and a fresh Spring process against a real TLS receiver. */
class WebhookJvmCrashIntegrationTests {
    Path temp;
    private final Set<String> secrets = new HashSet<>();
    private static final String JWT_TTL_ENV = "NEXUS_AUTH_JWT_ACCESSTOKENTTL";
    record Request(byte[] body, String timestamp, String signature) {}
    record Row(int status, String token, java.sql.Timestamp lease) {}

    @Test void forceKilledJvmIsRecoveredByNewJvm() throws Exception {
        String url = required("SPRING_DATASOURCE_URL"), username = required("SPRING_DATASOURCE_USERNAME"), password = required("SPRING_DATASOURCE_PASSWORD");
        try (Connection db = DriverManager.getConnection(url, username, password)) {
            try (var r = db.createStatement().executeQuery("SELECT DATABASE(), CURRENT_USER(), VERSION()")) {
                assertTrue(r.next()); requireAcceptanceDatabase(r.getString(1), r.getString(2), r.getString(3));
                System.out.println("Crash acceptance database version: " + r.getString(3));
            }
            org.flywaydb.core.Flyway.configure().dataSource(url, username, password).load().migrate();
            Path target = Path.of("target").toAbsolutePath().normalize();
            Files.createDirectories(target); Files.deleteIfExists(target.resolve("evidence/webhook-jvm-crash.json")); temp = Files.createTempDirectory(target, "webhook-crash-");
            try { runScenario(db); }
            catch (Exception | AssertionError failure) {
                emitFailureLogs();
                throw failure;
            }
            finally {
                try (var paths = Files.walk(temp)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }
    static void requireAcceptanceDatabase(String schema, String user, String version) {
        assertEquals("nexus_test", schema, "Crash acceptance requires the dedicated test schema");
        assertTrue(user.startsWith("nexus_test_user@"), "Crash acceptance requires the dedicated test account");
        assertTrue(version.matches("8\\.4\\.\\d+(?:[-+].*)?"), "Crash acceptance requires MySQL 8.4.x; actual: " + version);
    }
    private static String required(String name) { return Objects.requireNonNull(System.getenv(name), name + " must be set explicitly for crash acceptance"); }
    private void runScenario(Connection db) throws Exception {
        Path store = temp.resolve("server.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        Process generator = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "testpass", "-keypass", "testpass", "-dname", "CN=webhook.invalid", "-ext", "SAN=dns:webhook.invalid", "-validity", "1", "-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try { assertTrue(generator.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, generator.exitValue()); }
        finally { terminate(generator); }
        var ks = KeyStore.getInstance("PKCS12"); try (var in = Files.newInputStream(store)) { ks.load(in, "testpass".toCharArray()); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(ks, "testpass".toCharArray());
        var tls = SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(), null, null);
        var calls = new LinkedBlockingQueue<Request>(); var release = new CountDownLatch(1); Path inbox = Files.createDirectory(temp.resolve("receiver-inbox")); var businessCount = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpsServer.create();
        var executor = Executors.newCachedThreadPool();
        Process first = null, second = null; long user = 0, app = 0, webhook = 0, event = 0;
        try {
            server.setHttpsConfigurator(new HttpsConfigurator(tls));
            server.setExecutor(executor);
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/hook", ex -> {
                try {
                    byte[] body = ex.getRequestBody().readAllBytes(); String id = new ObjectMapper().readTree(body).get("eventId").asText();
                    UUID.fromString(id); try { Files.createFile(inbox.resolve(id)); businessCount.incrementAndGet(); } catch (FileAlreadyExistsException duplicate) { /* durable receiver deduplication */ }
                    calls.add(new Request(body, ex.getRequestHeaders().getFirst("X-Nexus-Timestamp"), ex.getRequestHeaders().getFirst("X-Nexus-Signature")));
                    release.await(20, TimeUnit.SECONDS); ex.sendResponseHeaders(200, -1);
                } catch (Exception ignored) { } finally { ex.close(); }
            }); server.start();
            byte[] master = new byte[32]; new SecureRandom().nextBytes(master); String master64 = Base64.getEncoder().encodeToString(master);
            var crypto = new WebhookSecretCrypto(new WebhookCryptoProperties(Map.of("v1", master64), "v1")); byte[] secret = crypto.generateSecret(); var encrypted = crypto.encrypt(secret);
            String eventId = UUID.randomUUID().toString();
            secrets.add(master64); secrets.add(Base64.getEncoder().encodeToString(secret));
            user = insert(db, "INSERT INTO users(email,display_name,password_hash) VALUES(?,?,?)", UUID.randomUUID()+"@example.test", "crash test", "test-placeholder");
            app = insert(db, "INSERT INTO applications(owner_user_id,name) VALUES(?,?)", user, "crash-"+UUID.randomUUID());
            webhook = insert(db, "INSERT INTO application_webhooks(application_id,url,status) VALUES(?,?,0)", app, "https://webhook.invalid:"+server.getAddress().getPort()+"/hook");
            insert(db, "INSERT INTO webhook_secrets(webhook_id,secret_ciphertext,secret_nonce,encryption_algorithm,encryption_key_version,secret_version,status) VALUES(?,?,?,?,?,1,0)", webhook, encrypted.ciphertext(), encrypted.nonce(), encrypted.encryptionAlgorithm(), encrypted.encryptionKeyVersion());
            event = insert(db, "INSERT INTO webhook_outbox(event_id,event_type,application_id,job_id,event_status,completed_at,query_url,status,attempt_count,next_attempt_at) VALUES(?,'screenshot.completed',?,?,1,UTC_TIMESTAMP(3),'/test',0,0,UTC_TIMESTAMP(3))", eventId, app, UUID.randomUUID().toString());
            first = child(store, master64, "first"); Request one = calls.poll(45, TimeUnit.SECONDS); assertNotNull(one, "first JVM did not send"); assertChildBinding(first, "first"); Row claimed = row(db, event); assertEquals(1, claimed.status()); assertNotNull(claimed.token());
            long firstPid = first.pid(); first.destroyForcibly(); assertTrue(first.waitFor(10, TimeUnit.SECONDS)); assertFalse(first.isAlive()); Instant killedAt = Instant.now();
            Row killed = row(db, event); assertEquals(1, killed.status()); assertEquals(claimed.token(), killed.token()); assertNotNull(killed.lease());
            Thread.sleep(300); Row unchanged = row(db, event); assertEquals(killed.lease(), unchanged.lease()); assertEquals(killed.token(), unchanged.token());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!expired(db, event) && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(expired(db, event), "original lease did not expire");
            second = child(store, master64, "second"); Request two = calls.poll(45, TimeUnit.SECONDS); assertNotNull(two, "new JVM did not recover and send"); assertChildBinding(second, "second"); Row recovered = row(db, event); Instant recoveredAt = Instant.now();
            assertEquals(1, recovered.status()); assertNotEquals(killed.token(), recovered.token());
            int staleUpdateCount = markDeliveredWithProductionMapper(event, killed.token());
            assertEquals(0, staleUpdateCount, "production Mapper must fence the old JVM's token after reclaim");
            Row afterStale = row(db, event);
            assertEquals(1, afterStale.status()); assertEquals(recovered.token(), afterStale.token(), "stale completion must leave the new claim unchanged");
            assertArrayEquals(one.body(), two.body()); assertEquals(eventId, new ObjectMapper().readTree(two.body()).get("eventId").asText());
            var sig = new WebhookSignatureService(); assertEquals(sig.sign(secret, Long.parseLong(one.timestamp()), one.body()), one.signature()); assertEquals(sig.sign(secret, Long.parseLong(two.timestamp()), two.body()), two.signature());
            assertNotEquals(one.timestamp(), two.timestamp()); assertNotEquals(one.signature(), two.signature()); assertEquals(1, businessCount.get()); assertTrue(Files.isRegularFile(inbox.resolve(eventId))); try (var entries = Files.list(inbox)) { assertEquals(1, entries.count()); }
            release.countDown(); deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10); while (row(db,event).status()!=2 && System.nanoTime()<deadline) Thread.sleep(50);
            assertEquals(2,row(db,event).status()); assertNull(calls.poll(500,TimeUnit.MILLISECONDS));
            Path evidence = Path.of("target", "evidence", "webhook-jvm-crash.json"); Files.createDirectories(evidence.getParent());
            new ObjectMapper().writeValue(evidence.toFile(), Map.ofEntries(
                    Map.entry("databaseVersion", db.getMetaData().getDatabaseProductVersion()), Map.entry("database", "nexus_test"),
                    Map.entry("firstPid", firstPid), Map.entry("secondPid", second.pid()), Map.entry("firstProcessDead", !first.isAlive()),
                    Map.entry("killedAtUtc", killedAt.toString()), Map.entry("recoveredAtUtc", recoveredAt.toString()),
                    Map.entry("eventId", eventId), Map.entry("deliveryCount", 2), Map.entry("durableBusinessEffects", businessCount.get()),
                    Map.entry("tokensDiffer", true), Map.entry("staleTokenUpdateCount", staleUpdateCount), Map.entry("staleTokenPath", "WebhookOutboxMapper.markDelivered"), Map.entry("childJwtTtl", "15m"), Map.entry("bothSignaturesValid", true),
                    Map.entry("timestampsDiffer", true), Map.entry("delivered", true)));
            System.out.println("Crash acceptance: force-killed PID " + firstPid + ", restarted PID " + second.pid() + ", 2 deliveries, 1 business effect, stale token fenced, delivered");
        } finally {
            release.countDown();
            try { terminate(first); }
            finally {
                try { terminate(second); }
                finally {
                    try { server.stop(0); }
                    finally {
                        executor.shutdownNow();
                        try { assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS), "receiver executor did not stop"); }
                        finally {
                            if(event!=0) update(db,"DELETE FROM webhook_outbox WHERE id=?",event); if(webhook!=0) { update(db,"DELETE FROM webhook_secrets WHERE webhook_id=?",webhook); update(db,"DELETE FROM application_webhooks WHERE id=?",webhook); } if(app!=0) update(db,"DELETE FROM applications WHERE id=?",app); if(user!=0) update(db,"DELETE FROM users WHERE id=?",user);
                        }
                    }
                }
            }
        }
    }
    private void assertChildBinding(Process child, String name) throws Exception {
        Path log = temp.resolve(name + ".log");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.readString(log).contains("Crash child JWT accessTokenTtl binding verified: 15m")
                && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(Files.readString(log).contains("Crash child JWT accessTokenTtl binding verified: 15m"),
                name + " JVM did not verify its bound JWT TTL");
    }
    private static int markDeliveredWithProductionMapper(long event, String oldToken) {
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("crash-acceptance", new JdbcTransactionFactory(),
                new UnpooledDataSource("com.mysql.cj.jdbc.Driver", required("SPRING_DATASOURCE_URL"),
                        required("SPRING_DATASOURCE_USERNAME"), required("SPRING_DATASOURCE_PASSWORD"))));
        config.addMapper(WebhookOutboxMapper.class);
        try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession()) {
            var mapper = session.getMapper(WebhookOutboxMapper.class);
            assertNotNull(mapper.lockForCompletion(event));
            int updated = mapper.markDelivered(event, oldToken);
            session.commit();
            return updated;
        }
    }
    private static void terminate(Process process) throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "test child process did not terminate");
        }
    }
    private void emitFailureLogs() {
        for (String child : List.of("first", "second")) {
            Path log = temp.resolve(child + ".log");
            try {
                if (Files.exists(log)) {
                    System.err.println("Crash acceptance " + child + " JVM log (sanitized tail):");
                    System.err.println(sanitizedTail(Files.readString(log), secrets));
                }
            } catch (java.io.IOException ignored) {
                System.err.println("Crash acceptance could not read " + child + " JVM log");
            }
        }
    }
    static String sanitizedTail(String log, Collection<String> secrets) {
        for (String secret : secrets.stream().filter(s -> s != null && !s.isEmpty())
                .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            log = log.replace(secret, "[REDACTED]");
        }
        log = log.replaceAll("(?i)(authorization[\"']?\\s*[:=]\\s*[\"']?)(?:Bearer|ApiKey)\\s+[^\\s,;\"']+", "$1[REDACTED]")
                .replaceAll("(?i)((?:password|secret|authorization|claim[_-]?token|signature)[\\w.-]*[\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)", "$1[REDACTED]")
                .replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[REDACTED-JWT]");
        var lines = log.lines().toList();
        String tail = String.join(System.lineSeparator(), lines.subList(Math.max(0, lines.size() - 80), lines.size()));
        return tail.substring(Math.max(0, tail.length() - 16000));
    }
    private Process child(Path store, String master, String name) throws Exception {
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path args = temp.resolve(name+".args"); Files.writeString(args, "-cp\n\""+cp.replace("\\", "\\\\").replace("\"", "\\\"")+"\"\ncom.nexus.webhook.dispatch.WebhookCrashChild\n--server.port=0\n--nexus.webhook.scheduling.enabled=true\n--nexus.webhook.dispatch.lease-duration=6s\n--nexus.webhook.dispatch.renewal-interval=2s\n--nexus.webhook.scheduling.dispatch-delay=100ms\n--nexus.webhook.scheduling.recovery-delay=100ms\n");
        var pb = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"@"+args).redirectErrorStream(true).redirectOutput(temp.resolve(name+".log").toFile());
        pb.environment().remove("NEXUS_AUTH_JWT_ACCESS_TOKEN_TTL");
        pb.environment().put("NEXUS_AUTH_JWT_ISSUER", "crash-test"); pb.environment().put(JWT_TTL_ENV, "15m"); pb.environment().put("NEXUS_AUTH_JWT_SECRET", Base64.getEncoder().encodeToString(SecureRandom.getSeed(32)));
        pb.environment().put("NEXUS_CRASH_TEST_KEYSTORE", store.toString()); pb.environment().put("NEXUS_WEBHOOK_CRYPTO_KEYS_V1",master); pb.environment().put("NEXUS_WEBHOOK_CRYPTO_CURRENT_KEY_VERSION","v1");
        pb.environment().forEach((key, value) -> {
            if (key.matches(".*(?:PASSWORD|SECRET|TOKEN|CRYPTO_KEYS).*")) secrets.add(value);
        });
        return pb.start();
    }
    private static long insert(Connection db,String sql,Object... values) throws SQLException { try(var p=db.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) { for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]); p.executeUpdate(); try(var r=p.getGeneratedKeys()){ if(!r.next())throw new SQLException("Missing ID"); return r.getLong(1); } } }
    private static void update(Connection db,String sql,long id) throws SQLException { try(var p=db.prepareStatement(sql)){p.setLong(1,id);p.executeUpdate();} }
    private static Row row(Connection db,long id) throws SQLException { try(var p=db.prepareStatement("SELECT status,claim_token,lease_expires_at FROM webhook_outbox WHERE id=?")){p.setLong(1,id);try(var r=p.executeQuery()){if(!r.next())throw new SQLException("Missing event");return new Row(r.getInt(1),r.getString(2),r.getTimestamp(3));}} }
    private static boolean expired(Connection db,long id) throws SQLException { try(var p=db.prepareStatement("SELECT lease_expires_at<=UTC_TIMESTAMP(6) FROM webhook_outbox WHERE id=?")){p.setLong(1,id);try(var r=p.executeQuery()){return r.next()&&r.getBoolean(1);}} }
}
