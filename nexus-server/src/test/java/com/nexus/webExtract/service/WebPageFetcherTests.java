package com.nexus.webExtract.service;

import com.nexus.webExtract.exception.InvalidWebExtractRequestException;
import com.nexus.webExtract.exception.WebPageFetchException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebPageFetcherTests {
    private static final URI ARTICLE_URI = URI.create("https://blog.csdn.net/user/article/details/123");
    private final WebExtractUrlPolicy policy = new WebExtractUrlPolicy();
    private final CloseableHttpClient client = mock(CloseableHttpClient.class);
    private final WebPageFetcher fetcher = new WebPageFetcher(policy, client);

    @Test
    void refusesInvalidUrlBeforeOpeningConnection() {
        assertThrows(InvalidWebExtractRequestException.class,
                () -> fetcher.fetch(URI.create("https://127.0.0.1/internal")));
        verifyNoInteractions(client);
    }

    @Test
    void returnsOnlyHtmlWithinSizeLimit() throws Exception {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
        response.setEntity(new StringEntity("<html><body>正文</body></html>", ContentType.TEXT_HTML));
        answerWith(response);
        assertTrue(fetcher.fetch(ARTICLE_URI).contains("正文"));
    }

    @Test
    void rejectsRedirectAndNonHtmlWithoutFollowingThem() throws Exception {
        BasicClassicHttpResponse redirect = new BasicClassicHttpResponse(302);
        redirect.addHeader("Location", "http://127.0.0.1/private");
        answerWith(redirect);
        assertThrows(WebPageFetchException.class, () -> fetcher.fetch(ARTICLE_URI));

        BasicClassicHttpResponse image = new BasicClassicHttpResponse(200);
        image.setEntity(new StringEntity("not html", ContentType.IMAGE_PNG));
        answerWith(image);
        assertThrows(WebPageFetchException.class, () -> fetcher.fetch(ARTICLE_URI));
    }

    @Test
    void stopsOversizedResponseEvenWithoutContentLength() throws Exception {
        BasicClassicHttpResponse response = new BasicClassicHttpResponse(200);
        response.setEntity(new StringEntity("x".repeat(2 * 1024 * 1024 + 1), ContentType.TEXT_HTML));
        answerWith(response);
        assertThrows(WebPageFetchException.class, () -> fetcher.fetch(ARTICLE_URI));
    }

    @Test
    void networkFailureDoesNotExposeUnderlyingUrlInApiException() throws Exception {
        doThrow(new IOException("https://blog.csdn.net/article?token=secret"))
                .when(client).execute(any(HttpGet.class), any(HttpClientResponseHandler.class));
        WebPageFetchException error = assertThrows(WebPageFetchException.class, () -> fetcher.fetch(ARTICLE_URI));
        assertFalse(error.getMessage().contains("token="));
        assertNull(error.getCause());
    }

    @SuppressWarnings("unchecked")
    private void answerWith(BasicClassicHttpResponse response) throws Exception {
        doAnswer(invocation -> {
            HttpClientResponseHandler<String> handler = invocation.getArgument(1);
            return handler.handleResponse(response);
        }).when(client).execute(any(HttpGet.class), any(HttpClientResponseHandler.class));
    }
}
