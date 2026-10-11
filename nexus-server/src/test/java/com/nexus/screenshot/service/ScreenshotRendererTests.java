package com.nexus.screenshot.service;

import com.nexus.screenshot.exception.ScreenshotException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nexus.screenshot.exception.ScreenshotException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScreenshotRendererTests {
    private static final URI PAGE = URI.create("https://blog.csdn.net/article");

    @Test
    void realChromiumRendersControlledResourcesWithoutScriptsOrPrivateConnections() throws Exception {
        AtomicInteger privateHits = new AtomicInteger();
        HttpServer sentinel = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sentinel.createContext("/", exchange -> { privateHits.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        sentinel.start();
        try {
            String privateUrl = "http://127.0.0.1:" + sentinel.getAddress().getPort() + "/private";
            String html = "<html><head><link rel='icon' href='data:,'><link rel='stylesheet' href='/style.css'></head><body>"
                    + "<img id='allowed' src='https://i-blog.csdnimg.cn/photo.png'><img src='" + privateUrl + "'><iframe src='" + privateUrl + "'></iframe>"
                    + "<script>document.body.style.background='purple';fetch('" + privateUrl + "');</script></body></html>";
            var fetcher = mock(ScreenshotResourceFetcher.class);
            when(fetcher.fetch(eq(PAGE), eq("document"), any())).thenReturn(text(html, "text/html; charset=utf-8"));
            when(fetcher.fetch(eq(URI.create("https://blog.csdn.net/style.css")), eq("stylesheet"), any()))
                    .thenReturn(text("body{margin:0;background:#22aa66}#allowed{position:absolute;left:20px;top:20px;width:40px;height:40px}", "text/css"));
            BufferedImage pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            pixel.setRGB(0, 0, 0x3366cc);
            ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(pixel, "png", png);
            when(fetcher.fetch(eq(URI.create("https://i-blog.csdnimg.cn/photo.png")), eq("image"), any()))
                    .thenReturn(new ScreenshotResource(png.toByteArray(), "image/png"));
            var result = new ScreenshotRenderer(new ScreenshotUrlPolicy(), fetcher).render(PAGE);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.png()));
            assertNotNull(image); assertEquals(1280, image.getWidth()); assertEquals(720, image.getHeight());
            assertEquals(0x22aa66, image.getRGB(5, 5) & 0xffffff); // CSS 生效，脚本没有把背景改成紫色。
            assertEquals(0x3366cc, image.getRGB(30, 30) & 0xffffff); // 图片来自受控 Fetcher。
            assertTrue(result.blockedResources() >= 2); assertEquals(0, privateHits.get());
            verify(fetcher, times(3)).fetch(any(), anyString(), any());
        } finally { sentinel.stop(0); }
    }

    @Test
    void primaryPageFailureIsNotMaskedAsSuccessfulBlankScreenshot() {
        var fetcher = mock(ScreenshotResourceFetcher.class);
        when(fetcher.fetch(any(), anyString(), any())).thenThrow(new ScreenshotException(PAGE_FETCH_FAILED));
        var error = assertThrows(ScreenshotException.class, () -> new ScreenshotRenderer(new ScreenshotUrlPolicy(), fetcher).render(PAGE));
        assertEquals(PAGE_FETCH_FAILED, error.kind()); assertNull(error.getCause());
    }

    private ScreenshotResource text(String body, String type) { return new ScreenshotResource(body.getBytes(StandardCharsets.UTF_8), type); }

    @Test
    void deniedResourcesBeyondAttemptLimitReturnPartialPng() throws Exception {
        StringBuilder html = new StringBuilder("<html><head><link rel='icon' href='data:,'></head><body>");
        for (int index = 0; index < 70; index++) html.append("<img src='https://unlisted.invalid/image-").append(index).append(".png'>");
        html.append("</body></html>");
        var fetcher = mock(ScreenshotResourceFetcher.class);
        when(fetcher.fetch(eq(PAGE), eq("document"), any())).thenReturn(text(html.toString(), "text/html; charset=utf-8"));
        var result = new ScreenshotRenderer(new ScreenshotUrlPolicy(), fetcher).render(PAGE);
        assertTrue(result.resourceLimitReached());
        assertEquals(70, result.blockedResources());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(result.png())));
        verify(fetcher, times(1)).fetch(any(), anyString(), any());
    }

    @Test
    void allowedResourcesAfterTheQuotaAreNotFetched() throws Exception {
        StringBuilder html = new StringBuilder("<html><head><link rel='icon' href='data:,'></head><body>");
        for (int index = 0; index < 70; index++) html.append("<img src='https://i-blog.csdnimg.cn/image-").append(index).append(".png'>");
        html.append("</body></html>");
        var fetcher = mock(ScreenshotResourceFetcher.class);
        var pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(pixel, "png", bytes);
        when(fetcher.fetch(any(), anyString(), any())).thenAnswer(invocation -> invocation.getArgument(1).equals("document")
                ? text(html.toString(), "text/html; charset=utf-8") : new ScreenshotResource(bytes.toByteArray(), "image/png"));
        var result = new ScreenshotRenderer(new ScreenshotUrlPolicy(), fetcher).render(PAGE);
        assertTrue(result.resourceLimitReached());
        assertEquals(7, result.blockedResources());
        verify(fetcher, times(64)).fetch(any(), anyString(), any());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(result.png())));
    }

    @Test
    void resourceByteOverflowStillFailsRatherThanReturningPartialSuccess() {
        var fetcher = mock(ScreenshotResourceFetcher.class);
        when(fetcher.fetch(eq(PAGE), eq("document"), any()))
                .thenReturn(text("<html><body><img src='https://i-blog.csdnimg.cn/large.png'></body></html>", "text/html"));
        when(fetcher.fetch(any(), eq("image"), any())).thenThrow(new ScreenshotException(LIMIT_EXCEEDED,
                ScreenshotException.DiagnosticReason.RESOURCE_BYTES, ScreenshotBudget.MAX_RESOURCE_BYTES + 1, ScreenshotBudget.MAX_RESOURCE_BYTES));
        var error = assertThrows(ScreenshotException.class, () -> new ScreenshotRenderer(new ScreenshotUrlPolicy(), fetcher).render(PAGE));
        assertEquals(ScreenshotException.DiagnosticReason.RESOURCE_BYTES, error.diagnostic().reason());
    }
}
