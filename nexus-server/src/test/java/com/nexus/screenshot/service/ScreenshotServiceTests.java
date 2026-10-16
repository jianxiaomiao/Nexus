package com.nexus.screenshot.service;

import com.nexus.screenshot.exception.ScreenshotException;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.quota.service.ApplicationQuotaService;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.concurrent.*;
import static com.nexus.screenshot.exception.ScreenshotException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScreenshotServiceTests {
    private final ScreenshotRenderer renderer = mock(ScreenshotRenderer.class);
    private final ApplicationQuotaService quota = mock(ApplicationQuotaService.class);
    private final ApiKeyIdentity identity = new ApiKeyIdentity(1L, 2L);
    private final ScreenshotService service = new ScreenshotService(new ScreenshotUrlPolicy(), renderer, quota);
    private static final String URL = "https://blog.csdn.net/article";
    @Test
    void invalidEntryNeverStartsBrowser() {
        assertThrows(ScreenshotException.class, () -> service.capture(identity, "https://127.0.0.1/private"));
        verifyNoInteractions(renderer);
        verifyNoInteractions(quota);
    }
    @Test
    void slotIsReleasedAfterSuccessAndFailure() {
        var result = new ScreenshotResult(new byte[]{1}, 0);
        when(renderer.render(any(URI.class))).thenThrow(new ScreenshotException(PAGE_FETCH_FAILED)).thenReturn(result);
        assertThrows(ScreenshotException.class, () -> service.capture(identity, URL));
        assertSame(result, service.capture(identity, URL));
        assertSame(result, service.capture(identity, URL));
    }
    @Test
    void overlappingCaptureIsRejectedWithoutStartingAnotherBrowser() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(renderer.render(any(URI.class))).thenAnswer(invocation -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return new ScreenshotResult(new byte[]{1}, 0); });
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<ScreenshotResult> first = executor.submit(() -> service.capture(identity, URL));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(BUSY, assertThrows(ScreenshotException.class, () -> service.capture(identity, URL)).kind());
                verify(renderer, times(1)).render(any(URI.class));
                verify(quota, times(1)).consumeOnce(identity);
            } finally { release.countDown(); }
            assertNotNull(first.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void quotaRejectionReleasesSlotWithoutStartingBrowser() {
        doThrow(new com.nexus.quota.exception.ApplicationQuotaExhaustedException())
                .doNothing().when(quota).consumeOnce(identity);
        assertThrows(com.nexus.quota.exception.ApplicationQuotaExhaustedException.class,
                () -> service.capture(identity, URL));
        verifyNoInteractions(renderer);
        var result = new ScreenshotResult(new byte[]{1}, 0);
        when(renderer.render(any(URI.class))).thenReturn(result);
        assertSame(result, service.capture(identity, URL));
        var order = inOrder(quota, renderer);
        order.verify(quota, times(2)).consumeOnce(identity);
        order.verify(renderer).render(any(URI.class));
    }
}
