package com.nexus.webExtract.service;

import com.nexus.webExtract.dto.WebExtractResponse;
import com.nexus.webExtract.exception.WebContentUnavailableException;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.extended.Readability4JExtended;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

import java.net.URI;

@Service
public class WebExtractService {
    private static final int MIN_TEXT_CHARS = 80;
    private final WebExtractUrlPolicy urlPolicy;
    private final WebPageFetcher pageFetcher;

    public WebExtractService(WebExtractUrlPolicy urlPolicy, WebPageFetcher pageFetcher) {
        this.urlPolicy = urlPolicy;
        this.pageFetcher = pageFetcher;
    }

    public WebExtractResponse extract(String rawUrl) {
        URI uri = urlPolicy.requireAllowedUrl(rawUrl);
        String html = pageFetcher.fetch(uri);
        Article article = new Readability4JExtended(uri.toString(), html).parse();
        if (article == null || article.getContent() == null || article.getTextContent() == null
                || article.getTextContent().strip().length() < MIN_TEXT_CHARS) {
            throw new WebContentUnavailableException("页面中没有可提取的文章正文");
        }

        // 提取库负责识别正文；jsoup 负责清除不可信 HTML 并保留图片在正文中的位置。
        Document body = Jsoup.parseBodyFragment(article.getContent(), uri.toString());
        body.select("base").remove();
        body.select("img").forEach(image -> {
            String absolute = image.absUrl("src");
            if (!absolute.startsWith("https://")) {
                absolute = image.absUrl("data-src");
            }
            if (!absolute.startsWith("https://")) {
                image.remove();
            } else {
                image.attr("src", absolute);
            }
        });
        Safelist safelist = Safelist.basicWithImages()
                .addTags("h1", "h2", "h3", "h4", "h5", "h6", "div")
                .addAttributes("img", "alt")
                .removeProtocols("img", "src", "http")
                .removeAttributes("a", "href");
        String contentHtml = Jsoup.clean(body.body().html(), uri.toString(), safelist);
        String textContent = article.getTextContent().trim();
        if (contentHtml.isBlank() || Jsoup.parseBodyFragment(contentHtml).text().isBlank()) {
            throw new WebContentUnavailableException("页面中没有可提取的文章正文");
        }
        return new WebExtractResponse(article.getTitle(), textContent, contentHtml);
    }
}
