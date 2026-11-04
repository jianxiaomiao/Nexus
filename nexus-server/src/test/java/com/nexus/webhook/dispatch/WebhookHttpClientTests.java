package com.nexus.webhook.dispatch;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WebhookHttpClientTests {
    @TempDir Path temp;

    @Test void directTlsUsesOriginalHostVerifiesCertificateAndDoesNotFollowRedirectOrReuse() throws Exception {
        Path store = temp.resolve("test.p12");
        Path keytoolPath = Path.of(System.getProperty("java.home"), "bin", "keytool");
        if (!Files.exists(keytoolPath)) keytoolPath = keytoolPath.resolveSibling("keytool.exe");
        String keytool = keytoolPath.toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "testpass", "-keypass", "testpass",
                "-dname", "CN=webhook.invalid", "-ext", "SAN=dns:webhook.invalid", "-validity", "1", "-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("keytool timed out"); }
        assertEquals(0, process.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) { keys.load(in, "testpass".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "testpass".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
        SSLContext serverTls = SSLContext.getInstance("TLS"); serverTls.init(km.getKeyManagers(), null, null);
        SSLContext clientTls = SSLContext.getInstance("TLS"); clientTls.init(null, tm.getTrustManagers(), null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        AtomicInteger requests = new AtomicInteger(), lookups = new AtomicInteger();
        AtomicReference<String> host = new AtomicReference<>(), sni = new AtomicReference<>();
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> timestampHeader = new AtomicReference<>(), signatureHeader = new AtomicReference<>();
        CountDownLatch releaseBody = new CountDownLatch(1);
        CountDownLatch cancelReceived = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); host.set(exchange.getRequestHeaders().getFirst("Host"));
            received.set(exchange.getRequestBody().readAllBytes());
            timestampHeader.set(exchange.getRequestHeaders().getFirst("X-Nexus-Timestamp"));
            signatureHeader.set(exchange.getRequestHeaders().getFirst("X-Nexus-Signature"));
            if (exchange.getRequestURI().getPath().equals("/cancel")) {
                cancelReceived.countDown();
                try { releaseBody.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
                return;
            }
            if (exchange.getRequestURI().getPath().equals("/held")) {
                exchange.sendResponseHeaders(200, 100);
                try { releaseBody.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
                return;
            }
            var names = ((ExtendedSSLSession) ((HttpsExchange) exchange).getSSLSession()).getRequestedServerNames();
            if (!names.isEmpty()) sni.set(((SNIHostName) names.getFirst()).getAsciiName());
            if (exchange.getRequestURI().getPath().equals("/invalid")) {
                exchange.getResponseHeaders().add("Retry-After", "not-a-date");
                exchange.sendResponseHeaders(429, -1); exchange.close(); return;
            }
            if (exchange.getRequestURI().getPath().equals("/rate")) {
                exchange.getResponseHeaders().add("Retry-After", "23");
                exchange.sendResponseHeaders(429, -1); exchange.close(); return;
            }
            exchange.getResponseHeaders().add("Location", "/redirected");
            exchange.getResponseHeaders().add("Retry-After", "23");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        ProxySelector previousSelector = ProxySelector.getDefault();
        try {
            ProxySelector.setDefault(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 1))); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            });
            AtomicReference<InetAddress> resolved = new AtomicReference<>(InetAddress.getLoopbackAddress());
            try (var client = new WebhookHttpClient(h -> { lookups.incrementAndGet(); return new InetAddress[]{resolved.get()}; }, clientTls, true)) {
                int port = server.getAddress().getPort();
                var event = new com.nexus.webhook.entity.WebhookOutbox(); event.setEventId("中文\"\\\n");
                byte[] body = WebhookPayload.from(event).toBytes(tools.jackson.databind.json.JsonMapper.builder().build());
                var signature = new com.nexus.webhook.crypto.WebhookSignatureService();
                String expectedSignature = signature.sign(new byte[]{1, 2, 3}, 123, body);
                var prepared = client.preparePost("https://webhook.invalid:" + port + "/", body, 123L, expectedSignature);
                resolved.set(InetAddress.getByName("192.168.1.1"));
                assertEquals(new WebhookHttpResponse(302, "23"), prepared.send());
                assertEquals(1, lookups.get());
                assertThrows(IllegalStateException.class, prepared::send);
                resolved.set(InetAddress.getLoopbackAddress());
                assertArrayEquals(body, received.get());
                assertEquals("123", timestampHeader.get());
                assertEquals(expectedSignature, signatureHeader.get());
                assertEquals(expectedSignature, signature.sign(new byte[]{1, 2, 3}, 123, received.get()));
                assertEquals(302, client.post("https://webhook.invalid:" + port + "/", new byte[0]).statusCode());
                assertEquals(2, requests.get()); assertEquals(2, lookups.get());
                var handler = new WebhookResponseHandler(new WebhookRetryPolicy(5, java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofMinutes(5)), java.time.Clock.systemUTC(), n -> n - 1);
                var rate = client.post("https://webhook.invalid:" + port + "/rate", new byte[0]);
                assertEquals(new WebhookHttpResponse(429, "23"), rate);
                assertEquals(DeliveryStatus.RETRY, handler.classify(rate, 0, false).status());
                assertEquals(java.time.Duration.ofSeconds(23), handler.classify(rate, 0, false).retryDelay());
                var invalid = client.post("https://webhook.invalid:" + port + "/invalid", new byte[0]);
                assertEquals(new WebhookHttpResponse(429, "not-a-date"), invalid);
                assertEquals(java.time.Duration.ofSeconds(1), handler.classify(invalid, 0, false).retryDelay());
                assertEquals("webhook.invalid:" + port, host.get()); assertEquals("webhook.invalid", sni.get());
                assertThrows(IOException.class, () -> client.post("https://wrong.invalid:" + port + "/", new byte[0]));
                assertEquals(4, requests.get());
                var cancellable = client.preparePost("https://webhook.invalid:" + port + "/cancel", body, 123L, expectedSignature);
                var cancelled = CompletableFuture.supplyAsync(() -> {
                    try { cancellable.send(); return false; }
                    catch (IOException e) { return true; }
                });
                assertTrue(cancelReceived.await(5, TimeUnit.SECONDS));
                cancellable.cancel();
                assertTrue(cancelled.get(3, TimeUnit.SECONDS));
                releaseBody.countDown();
                var pending = CompletableFuture.supplyAsync(() -> {
                    try { return client.post("https://webhook.invalid:" + port + "/held", new byte[0]); }
                    catch (IOException e) { throw new RuntimeException(e); }
                });
                try { assertEquals(new WebhookHttpResponse(200, null), pending.get(5, TimeUnit.SECONDS)); }
                finally { releaseBody.countDown(); }
            }
            try (var untrusted = new WebhookHttpClient(h -> new InetAddress[]{InetAddress.getLoopbackAddress()}, null, true)) {
                assertThrows(IOException.class, () -> untrusted.post("https://webhook.invalid:" + server.getAddress().getPort() + "/", new byte[0]));
                assertEquals(6, requests.get());
            }
        } finally {
            releaseBody.countDown();
            ProxySelector.setDefault(previousSelector);
            server.stop(0);
        }
    }

    @Test void productionAddressChecksRejectPrivateEvenWhenMixedWithPublic() throws Exception {
        try (var client = new WebhookHttpClient(h -> new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getLoopbackAddress()}, null, false)) {
            var gate = org.mockito.Mockito.mock(WebhookAttemptPreparer.class);
            assertThrows(WebhookDisallowedAddressException.class, () -> {
                var request = client.preparePost("https://webhook.invalid/", new byte[0]);
                gate.prepareAndSend(1, "token", new WebhookAttemptPreparer.Snapshot(0, false),
                        WebhookAttemptPreparer.AttemptType.INITIAL, request);
            });
            org.mockito.Mockito.verifyNoInteractions(gate);
        }
    }

    @Test void ipv4OnlyConfigurationFailsClosed() {
        String previous = System.getProperty("java.net.preferIPv4Stack");
        try {
            System.setProperty("java.net.preferIPv4Stack", "true");
            assertThrows(IllegalStateException.class, WebhookHttpClient::new);
        } finally {
            if (previous == null) System.clearProperty("java.net.preferIPv4Stack");
            else System.setProperty("java.net.preferIPv4Stack", previous);
        }
    }
}
