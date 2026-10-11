package com.nexus.screenshot.service;

import com.nexus.screenshot.exception.ScreenshotException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.nexus.screenshot.exception.ScreenshotException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScreenshotResourceFetcherTests {
    private static final URI PAGE = URI.create("https://blog.csdn.net/article");
    private final CloseableHttpClient client = mock(CloseableHttpClient.class);
    private final ScreenshotResourceFetcher fetcher = new ScreenshotResourceFetcher(new ScreenshotUrlPolicy(), client);
    @AfterEach void close() throws IOException { fetcher.close(); }

    @Test
    void deniesUnlistedTargetBeforeConnection() {
        assertThrows(ScreenshotException.class, () -> fetcher.fetch(URI.create("https://127.0.0.1/private"), "image", new ScreenshotBudget()));
        verifyNoInteractions(client);
    }
    @Test
    void returnsOriginalBytesAndSafeHeadersWithoutCredentials() throws Exception {
        byte[] bytes = {1, 2, 3};
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new ByteArrayEntity(bytes, ContentType.IMAGE_PNG));
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Set-Cookie", "private=value");
        doAnswer(invocation -> {
            HttpGet get = invocation.getArgument(0);
            assertNull(get.getFirstHeader("Authorization")); assertNull(get.getFirstHeader("Cookie"));
            assertEquals("Nexus-Screenshot/1.0", get.getFirstHeader("User-Agent").getValue());
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(response);
        }).when(client).execute(any(HttpGet.class), any(HttpClientResponseHandler.class));
        var resource = fetcher.fetch(URI.create("https://i-blog.csdnimg.cn/image.png"), "image", new ScreenshotBudget());
        assertArrayEquals(bytes, resource.bytes());
        assertEquals("*", resource.headers().get("Access-Control-Allow-Origin"));
        assertEquals(1, resource.headers().size());
    }
    @Test
    void rejectsRedirectBeforeAnyFollowupRequest() throws Exception {
        var response = new BasicClassicHttpResponse(302);
        response.addHeader("Location", "http://127.0.0.1/private");
        answer(response);
        var error = assertThrows(ScreenshotException.class, () -> fetcher.fetch(PAGE, "document", new ScreenshotBudget()));
        assertEquals(PAGE_FETCH_FAILED, error.kind());
        assertEquals(new ScreenshotException.Diagnostic(ScreenshotException.DiagnosticReason.UPSTREAM_STATUS, 302, 200), error.diagnostic());
        verify(client, times(1)).execute(any(HttpGet.class), any(HttpClientResponseHandler.class));
    }
    @Test
    void refusesTypeMismatchAndResourceSizeOverflow() throws Exception {
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new StringEntity("not css", ContentType.TEXT_HTML)); answer(response);
        assertEquals(ScreenshotException.DiagnosticReason.RESOURCE_TYPE,
                assertThrows(ScreenshotException.class, () -> fetcher.fetch(PAGE, "stylesheet", new ScreenshotBudget())).diagnostic().reason());
        response.setEntity(new StringEntity("x".repeat(ScreenshotBudget.MAX_RESOURCE_BYTES + 1), ContentType.TEXT_HTML));
        var error = assertThrows(ScreenshotException.class, () -> fetcher.fetch(PAGE, "document", new ScreenshotBudget()));
        assertEquals(LIMIT_EXCEEDED, error.kind());
        assertEquals(new ScreenshotException.Diagnostic(ScreenshotException.DiagnosticReason.RESOURCE_BYTES,
                ScreenshotBudget.MAX_RESOURCE_BYTES + 1, ScreenshotBudget.MAX_RESOURCE_BYTES), error.diagnostic());
    }
    @Test
    void dnsAndNetworkDiagnosticsDoNotRetainUpstreamUrlsOrCauses() throws Exception {
        for (IOException upstream : new IOException[]{new UnknownHostException("private-host?secret=never-log"),
                new IOException("https://blog.csdn.net/article?token=never-log")}) {
            doThrow(upstream).when(client).execute(any(HttpGet.class), any(HttpClientResponseHandler.class));
            var error = assertThrows(ScreenshotException.class, () -> fetcher.fetch(PAGE, "document", new ScreenshotBudget()));
            assertEquals(upstream instanceof UnknownHostException ? ScreenshotException.DiagnosticReason.DNS_FAILURE
                    : ScreenshotException.DiagnosticReason.NETWORK_IO, error.diagnostic().reason());
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("never-log"));
            assertFalse(error.diagnostic().toString().contains("never-log"));
        }
    }
    @Test
    void deadlineCancelsTheActualRequestAndKeepsUrlsOutOfException() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        doAnswer(invocation -> {
            HttpGet get = invocation.getArgument(0);
            while (!get.isCancelled()) Thread.sleep(5);
            cancelled.set(true);
            throw new IOException("https://blog.csdn.net/article?token=private");
        }).when(client).execute(any(HttpGet.class), any(HttpClientResponseHandler.class));
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            var budget = new ScreenshotBudget(System::nanoTime, Duration.ofMillis(150), 10, 1000);
            var error = assertThrows(ScreenshotException.class, () -> fetcher.fetch(PAGE, "document", budget));
            assertEquals(TIMEOUT, error.kind()); assertNull(error.getCause()); assertFalse(error.getMessage().contains("token="));
        });
        assertTrue(cancelled.get());
    }
    @SuppressWarnings("unchecked")
    private void answer(BasicClassicHttpResponse response) throws Exception {
        doAnswer(invocation -> ((HttpClientResponseHandler<Object>) invocation.getArgument(1)).handleResponse(response))
                .when(client).execute(any(HttpGet.class), any(HttpClientResponseHandler.class));
    }
}
