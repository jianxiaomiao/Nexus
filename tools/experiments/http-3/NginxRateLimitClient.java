import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Executes assertions against the actual Nginx listener on port 18080. */
public class NginxRateLimitClient {
    private static final URI NGINX = URI.create("http://127.0.0.1:18080");
    private static final URI CONTROL = URI.create("http://127.0.0.1:18081");
    private static final String AUTH = "ApiKey experiment-app-a";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public static void main(String[] args) throws Exception {
        verifyAuthenticationAndJson();
        verifyPng();
        verifyBurstAndTimeouts();
        System.out.println("HTTP-3 PASS: Nginx preserved JSON, PNG, 401 and both 429 responses; rejected requests did not consume quota.");
    }

    private static void verifyAuthenticationAndJson() throws Exception {
        reset(false);
        HttpResponse<byte[]> unauthorized = get("/v1/json", "ApiKey invalid");
        require(unauthorized.statusCode() == 401, "invalid credential should return 401");
        require(contentType(unauthorized).startsWith("application/json"), "401 should remain JSON");
        require(text(unauthorized).contains("INVALID_ACCESS_TOKEN"), "401 error code missing");
        require(stateValue("rateLimitCalls") == 0, "401 must not enter limiter");
        require(stateValue("quotaUsed") == 0, "401 must not consume quota");

        HttpResponse<byte[]> response = get("/v1/json", AUTH);
        require(response.statusCode() == 200, "JSON endpoint should return 200");
        require(contentType(response).startsWith("application/json"), "JSON content type changed through Nginx");
        require(text(response).contains("\"code\":\"OK\""), "JSON body changed through Nginx");
        require(stateValue("quotaUsed") == 1, "successful JSON request should consume quota once");
        System.out.println("JSON: invalid=401 without limiter/quota; valid=200 application/json with quotaUsed=1");
    }

    private static void verifyPng() throws Exception {
        reset(false);
        HttpResponse<byte[]> response = get("/v1/png", AUTH);
        require(response.statusCode() == 200, "PNG endpoint should return 200");
        require(contentType(response).startsWith("image/png"), "PNG content type changed through Nginx");
        byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        require(response.body().length > signature.length, "PNG response is too short");
        require(Arrays.equals(signature, Arrays.copyOf(response.body(), signature.length)), "PNG signature changed");
        require(stateValue("quotaUsed") == 1, "successful PNG request should consume quota once");
        System.out.println("PNG: 200 image/png; binary signature intact; quotaUsed=1");
    }

    private static void verifyBurstAndTimeouts() throws Exception {
        reset(true);
        int requestCount = 12;
        var executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<TimedResponse>> futures = new ArrayList<>();
        for (int index = 0; index < requestCount; index++) {
            final int requestIndex = index;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                long before = System.nanoTime();
                HttpResponse<byte[]> response = get("/v1/json?request=" + requestIndex, AUTH);
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
                return new TimedResponse(response, elapsedMillis);
            }));
        }
        require(ready.await(2, TimeUnit.SECONDS), "load clients did not become ready");
        start.countDown();

        int success = 0;
        int full = 0;
        int timeout = 0;
        long minimumTimeoutMillis = Long.MAX_VALUE;
        for (var future : futures) {
            TimedResponse timed = future.get(5, TimeUnit.SECONDS);
            HttpResponse<byte[]> response = timed.response;
            if (response.statusCode() == 200) {
                success++;
                continue;
            }
            require(response.statusCode() == 429, "burst should return only 200 or 429");
            require(contentType(response).startsWith("application/json"), "429 should remain JSON");
            require("1".equals(response.headers().firstValue("Retry-After").orElse(null)), "429 Retry-After missing");
            String body = text(response);
            if (body.contains("APPLICATION_RATE_LIMIT_QUEUE_FULL")) {
                full++;
                require(timed.elapsedMillis < 900, "queue-full rejection should be immediate");
            } else if (body.contains("APPLICATION_RATE_LIMIT_WAIT_TIMEOUT")) {
                timeout++;
                minimumTimeoutMillis = Math.min(minimumTimeoutMillis, timed.elapsedMillis);
            } else {
                throw new AssertionError("unexpected 429 body: " + body);
            }
        }
        executor.shutdownNow();

        require(success == 1, "expected one immediate success, got " + success);
        require(full == 1, "expected one queue-full rejection, got " + full);
        require(timeout == 10, "expected ten wait timeouts, got " + timeout);
        require(minimumTimeoutMillis >= 900, "timeout response returned before the one-second boundary");
        require(stateValue("quotaUsed") == 1, "only the successful burst request may consume quota");
        require(stateValue("quotaCalls") == 1, "429 requests must not call quota");
        require(stateValue("businessCalls") == 1, "429 requests must not run business response");
        require(stateValue("waiting") == 0, "timed-out waiters must release capacity");
        System.out.println("burst: 1x200, 1x429 queue-full, 10x429 wait-timeout; quotaUsed=1; waiting=0");
    }

    private static void reset(boolean paused) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(CONTROL.resolve("/__experiment/reset?paused=" + paused))
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 200, "reset failed");
    }

    private static int stateValue(String name) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(CONTROL.resolve("/__experiment/state"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        String body = CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body();
        String marker = "\"" + name + "\":";
        int start = body.indexOf(marker);
        require(start >= 0, "state property missing: " + name);
        start += marker.length();
        int end = start;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return Integer.parseInt(body.substring(start, end));
    }

    private static HttpResponse<byte[]> get(String path, String authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(NGINX.resolve(path))
                .timeout(Duration.ofSeconds(4))
                .header("Authorization", authorization)
                .GET().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record TimedResponse(HttpResponse<byte[]> response, long elapsedMillis) {}
}
