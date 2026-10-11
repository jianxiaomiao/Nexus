package com.nexus.screenshot.service;

import com.nexus.common.net.PublicWebDnsResolver;
import com.nexus.screenshot.exception.ScreenshotException;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.nexus.screenshot.exception.ScreenshotException.Kind.*;
import static com.nexus.screenshot.exception.ScreenshotException.DiagnosticReason.*;

@Component
public class ScreenshotResourceFetcher {
    private final ScreenshotUrlPolicy policy;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService cancellation = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "screenshot-http-deadline");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public ScreenshotResourceFetcher(ScreenshotUrlPolicy policy) {
        this(policy, HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new PublicWebDnsResolver(policy::isAllowedResourceHost))
                        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3)).build())
                        .setMaxConnTotal(4).setMaxConnPerRoute(2).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build());
    }
    ScreenshotResourceFetcher(ScreenshotUrlPolicy policy, CloseableHttpClient client) { this.policy = policy; this.client = client; }

    public ScreenshotResource fetch(URI uri, String resourceType, ScreenshotBudget budget) {
        policy.requireResource(uri.toString());
        HttpGet get = new HttpGet(uri);
        long remaining = budget.remainingMillis();
        get.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofMilliseconds(Math.min(remaining, 4000)))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.min(remaining, 1000))).build());
        get.setHeader("User-Agent", "Nexus-Screenshot/1.0");
        // 不复制浏览器 Cookie、Authorization 或管理端凭据。
        var deadline = cancellation.schedule(get::cancel, remaining, TimeUnit.MILLISECONDS);
        try {
            return client.execute(get, response -> {
                if (response.getCode() != 200 || response.getEntity() == null)
                    throw new ScreenshotException(PAGE_FETCH_FAILED, UPSTREAM_STATUS, response.getCode(), 200);
                var entity = response.getEntity();
                String type = entity.getContentType();
                if (!allowedContentType(resourceType, type)) throw new ScreenshotException(PAGE_FETCH_FAILED, RESOURCE_TYPE);
                if (entity.getContentLength() > ScreenshotBudget.MAX_RESOURCE_BYTES)
                    throw new ScreenshotException(LIMIT_EXCEEDED, RESOURCE_BYTES, entity.getContentLength(), ScreenshotBudget.MAX_RESOURCE_BYTES);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = entity.getContent()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        budget.bytesRead(count);
                        if (output.size() + count > ScreenshotBudget.MAX_RESOURCE_BYTES)
                            throw new ScreenshotException(LIMIT_EXCEEDED, RESOURCE_BYTES, output.size() + count, ScreenshotBudget.MAX_RESOURCE_BYTES);
                        output.write(buffer, 0, count);
                    }
                }
                budget.remainingMillis();
                // 仅保留字体等跨域资源需要的响应信息，不转交 Cookie 或压缩/长度头。
                var cors = response.getFirstHeader("Access-Control-Allow-Origin");
                Map<String, String> headers = cors == null ? Map.of() : Map.of("Access-Control-Allow-Origin", cors.getValue());
                return new ScreenshotResource(output.toByteArray(), type, headers);
            });
        } catch (IOException exception) {
            budget.remainingMillis();
            boolean dnsFailure = false;
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof UnknownHostException) { dnsFailure = true; break; }
            }
            throw new ScreenshotException(PAGE_FETCH_FAILED, dnsFailure ? DNS_FAILURE : NETWORK_IO);
        } finally { deadline.cancel(false); }
    }

    static boolean allowedContentType(String resourceType, String type) {
        if (type == null) return false;
        String media = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (resourceType) {
            case "document" -> media.equals("text/html") || media.equals("application/xhtml+xml");
            case "stylesheet" -> media.equals("text/css");
            case "image" -> Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml", "image/avif").contains(media);
            case "font" -> Set.of("font/woff", "font/woff2", "font/ttf", "font/otf", "application/font-woff", "application/vnd.ms-fontobject").contains(media);
            default -> false;
        };
    }

    @PreDestroy
    public void close() throws IOException { cancellation.shutdownNow(); client.close(); }
}
