package com.nexus.screenshot.service;

import com.nexus.screenshot.exception.ScreenshotException;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.concurrent.*;
import static com.nexus.screenshot.exception.ScreenshotException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScreenshotServiceTests {
    private final ScreenshotRenderer renderer = mock(ScreenshotRenderer.class);
    private final ScreenshotService service = new ScreenshotService(new ScreenshotUrlPolicy(), renderer);
    private static final String URL = "https://blog.csdn.net/article";
    @Test
    void invalidEntryNeverStartsBrowser() {
        assertThrows(ScreenshotException.class, () -> service.capture("https://127.0.0.1/private"));
        verifyNoInteractions(renderer);
    }
    @Test
    void slotIsReleasedAfterSuccessAndFailure() {
        var result = new ScreenshotResult(new byte[]{1}, 0);
        when(renderer.render(any(URI.class))).thenThrow(new ScreenshotException(PAGE_FETCH_FAILED)).thenReturn(result);
        assertThrows(ScreenshotException.class, () -> service.capture(URL));
        assertSame(result, service.capture(URL));
        assertSame(result, service.capture(URL));
    }
    @Test
    void overlappingCaptureIsRejectedWithoutStartingAnotherBrowser() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(renderer.render(any(URI.class))).thenAnswer(invocation -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return new ScreenshotResult(new byte[]{1}, 0); });
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<ScreenshotResult> first = executor.submit(() -> service.capture(URL));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(BUSY, assertThrows(ScreenshotException.class, () -> service.capture(URL)).kind());
                verify(renderer, times(1)).render(any(URI.class));
            } finally { release.countDown(); }
            assertNotNull(first.get(3, TimeUnit.SECONDS));
        }
    }
}
