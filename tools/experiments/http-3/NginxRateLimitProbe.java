import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Isolated HTTP probe for authentication -> rate limit -> quota -> response.
 * It uses fake credentials and in-memory counters; it does not connect to Nexus or MySQL.
 */
public class NginxRateLimitProbe {
    private static final long APPLICATION_ID = 9001L;
    private static final String VALID_AUTHORIZATION = "ApiKey experiment-app-a";
    private static final byte[] PNG = createPng();

    private static final Counters COUNTERS = new Counters();
    private static final PausableLimiter LIMITER = new PausableLimiter(10, Duration.ofSeconds(1));

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18081), 64);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/json", exchange -> handleMachineRequest(exchange, false));
        server.createContext("/v1/png", exchange -> handleMachineRequest(exchange, true));
        server.createContext("/__experiment/reset", NginxRateLimitProbe::reset);
        server.createContext("/__experiment/state", NginxRateLimitProbe::state);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LIMITER.close();
            server.stop(0);
        }));
        server.start();
        System.out.println("HTTP-3 Java probe listening on 127.0.0.1:18081");
    }

    private static void handleMachineRequest(HttpExchange exchange, boolean png) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendJson(exchange, 405, "{\"code\":\"METHOD_NOT_ALLOWED\"}", null);
            return;
        }

        COUNTERS.authenticationCalls++;
        if (!VALID_AUTHORIZATION.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            sendJson(exchange, 401, "{\"code\":\"INVALID_ACCESS_TOKEN\",\"message\":\"invalid credential\"}", null);
            return;
        }

        COUNTERS.rateLimitCalls++;
        PausableLimiter.Decision decision;
        try {
            decision = LIMITER.acquire(APPLICATION_ID).get(2, TimeUnit.SECONDS);
        } catch (Exception exception) {
            sendJson(exchange, 500, "{\"code\":\"EXPERIMENT_FAILURE\"}", null);
            return;
        }
        if (decision == PausableLimiter.Decision.FULL) {
            sendJson(exchange, 429,
                    "{\"code\":\"APPLICATION_RATE_LIMIT_QUEUE_FULL\",\"message\":\"waiting queue is full\"}",
                    "1");
            return;
        }
        if (decision == PausableLimiter.Decision.TIMEOUT) {
            sendJson(exchange, 429,
                    "{\"code\":\"APPLICATION_RATE_LIMIT_WAIT_TIMEOUT\",\"message\":\"waited longer than one second\"}",
                    "1");
            return;
        }

        COUNTERS.quotaCalls++;
        COUNTERS.quotaUsed.merge(APPLICATION_ID, 1, Integer::sum);
        COUNTERS.businessCalls++;
        if (png) {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, PNG.length);
            try (var output = exchange.getResponseBody()) {
                output.write(PNG);
            } finally {
                exchange.close();
            }
        } else {
            sendJson(exchange, 200, "{\"code\":\"OK\",\"data\":{\"applicationId\":9001}}", null);
        }
    }

    private static void reset(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            sendJson(exchange, 405, "{\"code\":\"METHOD_NOT_ALLOWED\"}", null);
            return;
        }
        boolean paused = "paused=true".equals(exchange.getRequestURI().getRawQuery());
        LIMITER.reset(paused);
        COUNTERS.reset();
        sendJson(exchange, 200, "{\"paused\":" + paused + "}", null);
    }

    private static void state(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            sendJson(exchange, 405, "{\"code\":\"METHOD_NOT_ALLOWED\"}", null);
            return;
        }
        String body = "{\"authenticationCalls\":" + COUNTERS.authenticationCalls
                + ",\"rateLimitCalls\":" + COUNTERS.rateLimitCalls
                + ",\"quotaCalls\":" + COUNTERS.quotaCalls
                + ",\"quotaUsed\":" + COUNTERS.quotaUsed.getOrDefault(APPLICATION_ID, 0)
                + ",\"businessCalls\":" + COUNTERS.businessCalls
                + ",\"waiting\":" + LIMITER.waitingCount() + "}";
        sendJson(exchange, 200, body, null);
    }

    private static void sendJson(HttpExchange exchange, int status, String json, String retryAfter) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (retryAfter != null) {
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
        }
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        } finally {
            exchange.close();
        }
    }

    private static byte[] createPng() {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, Color.BLUE.getRGB());
            image.setRGB(1, 0, Color.WHITE.getRGB());
            image.setRGB(0, 1, Color.WHITE.getRGB());
            image.setRGB(1, 1, Color.BLUE.getRGB());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final class Counters {
        volatile int authenticationCalls;
        volatile int rateLimitCalls;
        volatile int quotaCalls;
        volatile int businessCalls;
        final Map<Long, Integer> quotaUsed = new HashMap<>();

        synchronized void reset() {
            authenticationCalls = 0;
            rateLimitCalls = 0;
            quotaCalls = 0;
            businessCalls = 0;
            quotaUsed.clear();
        }
    }

    private static final class PausableLimiter implements AutoCloseable {
        enum Decision { ALLOWED, FULL, TIMEOUT, RESET }

        private final int maxWaiters;
        private final long waitTimeoutNanos;
        private final long intervalNanos = Duration.ofMillis(100).toNanos();
        private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private boolean initialized;
        private boolean paused;
        private long nextPermitNanos;

        PausableLimiter(int maxWaiters, Duration waitTimeout) {
            this.maxWaiters = maxWaiters;
            this.waitTimeoutNanos = waitTimeout.toNanos();
            scheduler.scheduleAtFixedRate(this::dispatch, 5, 5, TimeUnit.MILLISECONDS);
        }

        synchronized CompletableFuture<Decision> acquire(long applicationId) {
            long now = System.nanoTime();
            if (!initialized) {
                initialized = true;
                nextPermitNanos = now + intervalNanos;
                return CompletableFuture.completedFuture(Decision.ALLOWED);
            }
            if (!paused && waiters.isEmpty() && now >= nextPermitNanos) {
                nextPermitNanos = now + intervalNanos;
                return CompletableFuture.completedFuture(Decision.ALLOWED);
            }
            if (waiters.size() >= maxWaiters) {
                return CompletableFuture.completedFuture(Decision.FULL);
            }
            Waiter waiter = new Waiter(now + waitTimeoutNanos);
            waiters.addLast(waiter);
            scheduler.schedule(() -> timeout(waiter), waitTimeoutNanos, TimeUnit.NANOSECONDS);
            return waiter.result;
        }

        private void dispatch() {
            List<Waiter> allowed = new ArrayList<>();
            synchronized (this) {
                if (paused) {
                    return;
                }
                long now = System.nanoTime();
                while (!waiters.isEmpty() && now > waiters.peekFirst().deadlineNanos) {
                    Waiter expired = waiters.removeFirst();
                    expired.result.complete(Decision.TIMEOUT);
                }
                if (!waiters.isEmpty() && now >= nextPermitNanos) {
                    allowed.add(waiters.removeFirst());
                    nextPermitNanos = now + intervalNanos;
                }
            }
            allowed.forEach(waiter -> waiter.result.complete(Decision.ALLOWED));
        }

        private void timeout(Waiter waiter) {
            synchronized (this) {
                if (waiters.remove(waiter)) {
                    waiter.result.complete(Decision.TIMEOUT);
                }
            }
        }

        synchronized int waitingCount() {
            return waiters.size();
        }

        synchronized void reset(boolean pauseReleases) {
            for (Waiter waiter : waiters) {
                waiter.result.complete(Decision.RESET);
            }
            waiters.clear();
            initialized = false;
            paused = pauseReleases;
            nextPermitNanos = 0;
        }

        @Override
        public void close() {
            scheduler.shutdownNow();
        }

        private static final class Waiter {
            final long deadlineNanos;
            final CompletableFuture<Decision> result = new CompletableFuture<>();

            Waiter(long deadlineNanos) {
                this.deadlineNanos = deadlineNanos;
            }
        }
    }
}
