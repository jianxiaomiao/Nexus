package com.nexus.webExtract.service;

import com.nexus.webExtract.dto.WebExtractResponse;
import com.nexus.webExtract.exception.WebContentUnavailableException;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebExtractServiceTests {
    private final WebPageFetcher fetcher = mock(WebPageFetcher.class);
    private final WebExtractService service = new WebExtractService(new WebExtractUrlPolicy(), fetcher);

    @Test
    void extractsChineseArticleAndKeepsImageInSanitizedHtml() {
        String paragraph = "这是一篇用于测试网页正文提取的中文技术文章，介绍如何在服务端安全地获取页面并保留正文图片。".repeat(15);
        String html = "<html><head><title>网页提取示例</title></head><body>"
                + "<nav>首页 频道 广告</nav><article><h1>网页提取示例</h1>"
                + "<p>" + paragraph + "</p>"
                + "<img src='/images/diagram.png' alt='流程图' onerror='alert(1)'>"
                + "<img src='data:image/gif;base64,R0lGODlhAQABAAD' data-src='//cdn.example.com/article.jpg' alt='配图'>"
                + "<p>" + paragraph + "</p></article><aside>推荐内容</aside></body></html>";
        when(fetcher.fetch(any(URI.class))).thenReturn(html);

        WebExtractResponse result = service.extract("https://blog.csdn.net/user/article/details/123");

        assertTrue(result.textContent().contains("用于测试网页正文提取"));
        assertTrue(result.contentHtml().contains("https://blog.csdn.net/images/diagram.png"));
        assertTrue(result.contentHtml().contains("https://cdn.example.com/article.jpg"));
        assertFalse(result.contentHtml().contains("onerror"));
        assertFalse(result.contentHtml().contains("data:image"));
        assertFalse(result.contentHtml().contains("<script"));
    }

    @Test
    void rejectsPageWithoutArticleBody() {
        when(fetcher.fetch(any(URI.class)))
                .thenReturn("<html><head><title>搜索结果</title></head><body><nav>首页 搜索 图片</nav></body></html>");
        assertThrows(WebContentUnavailableException.class,
                () -> service.extract("https://blog.csdn.net/search"));
    }
}
