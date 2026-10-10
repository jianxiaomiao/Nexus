package com.nexus.webExtract.service;

import com.nexus.webExtract.exception.WebPageFetchException;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class WebPageFetcher {
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;
    private final WebExtractUrlPolicy policy;
    private final CloseableHttpClient client;

    @Autowired
    public WebPageFetcher(WebExtractUrlPolicy policy) {
        this.policy = policy;
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PublicWebDnsResolver(policy))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(3)).build())
                .setMaxConnTotal(16)
                .setMaxConnPerRoute(8)
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                        .setResponseTimeout(Timeout.ofSeconds(6)).build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .build();
    }

    WebPageFetcher(WebExtractUrlPolicy policy, CloseableHttpClient client) {
        this.policy = policy;
        this.client = client;
    }

    public String fetch(URI uri) {
        policy.requireAllowedUrl(uri.toString());
        HttpGet get = new HttpGet(uri);
        get.setHeader("Accept", "text/html, application/xhtml+xml");
        get.setHeader("User-Agent", "Nexus-WebExtract/1.0");
        try {
            return client.execute(get, response -> {
                int status = response.getCode();
                if (status >= 300 && status < 400) {
                    throw new WebPageFetchException("目标页面发生跳转，暂不自动跟随");
                }
                if (status != 200) {
                    throw new WebPageFetchException("目标页面无法获取（HTTP " + status + "）");
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    throw new WebPageFetchException("目标页面没有 HTML 内容");
                }
                String contentType = entity.getContentType();
                String mediaType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
                if (!mediaType.startsWith("text/html") && !mediaType.startsWith("application/xhtml+xml")) {
                    throw new WebPageFetchException("目标页面不是 HTML 内容");
                }
                if (entity.getContentLength() > MAX_HTML_BYTES) {
                    throw new WebPageFetchException("目标 HTML 超过大小限制");
                }
                byte[] bytes;
                try (InputStream body = entity.getContent()) {
                    bytes = body.readNBytes(MAX_HTML_BYTES + 1);
                }
                if (bytes.length > MAX_HTML_BYTES) {
                    throw new WebPageFetchException("目标 HTML 超过大小限制");
                }
                return new String(bytes, charsetOf(contentType));
            });
        } catch (IOException exception) {
            // 目标 URL 的查询参数可能含敏感信息；异常链也可能带完整 URL，避免进入 5xx 日志。
            throw new WebPageFetchException("目标页面暂时无法访问");
        }
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
                    try {
                        return Charset.forName(trimmed.substring(8).replace("\"", "").trim());
                    } catch (IllegalArgumentException ignored) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    @PreDestroy
    public void close() throws IOException {
        client.close();
    }
}
