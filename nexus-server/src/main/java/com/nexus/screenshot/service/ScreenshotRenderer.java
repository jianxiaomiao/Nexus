package com.nexus.screenshot.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.microsoft.playwright.options.WaitUntilState;
import com.nexus.screenshot.exception.ScreenshotException;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.nexus.screenshot.exception.ScreenshotException.Kind.*;

@Component
public class ScreenshotRenderer {
    private static final Logger log = LoggerFactory.getLogger(ScreenshotRenderer.class);
    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;
    private final ScreenshotUrlPolicy policy;
    private final ScreenshotResourceFetcher fetcher;

    public ScreenshotRenderer(ScreenshotUrlPolicy policy, ScreenshotResourceFetcher fetcher) { this.policy = policy; this.fetcher = fetcher; }

    public ScreenshotResult render(URI uri) {
        ScreenshotBudget budget = new ScreenshotBudget();
        State state = new State();
        // 每次调用在当前线程创建、使用、关闭 Playwright，避免共享非线程安全的浏览器对象。
        // 浏览器由部署/本地准备步骤预先安装；不能在机器 API 请求中自动下载。
        try (BrowserNetworkGuard guard = new BrowserNetworkGuard();
             Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true).setChromiumSandbox(true).setTimeout(budget.remainingMillis())
                     .setProxy(new Proxy(guard.proxyUrl()))
                     .setArgs(List.of("--proxy-bypass-list=<-loopback>", "--disable-background-networking", "--disable-quic",
                             "--dns-prefetch-disable", "--host-resolver-rules=MAP * ~NOTFOUND")));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(WIDTH, HEIGHT)
                     .setJavaScriptEnabled(false).setServiceWorkers(ServiceWorkerPolicy.BLOCK).setAcceptDownloads(false).setOffline(true))) {
            context.route("**/*", route -> handleResource(route, uri, budget, state));
            Page page = context.newPage();
            try {
                page.navigate(uri.toString(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout(budget.remainingMillis()));
                if (state.failure != null) throw state.failure;
                if (!state.mainServed) throw new ScreenshotException(PAGE_FETCH_FAILED);
                byte[] png = page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.PNG)
                        .setFullPage(false).setTimeout(budget.remainingMillis()));
                if (state.failure != null) throw state.failure;
                budget.remainingMillis();
                if (png.length > 5 * 1024 * 1024)
                    throw new ScreenshotException(LIMIT_EXCEEDED, ScreenshotException.DiagnosticReason.PNG_BYTES, png.length, 5 * 1024 * 1024);
                log.info("截图资源统计 observed={}, fetched={}, blocked={}, truncated={}, limitReached={}, byType={}",
                        state.observed, state.fetched, state.blocked, state.truncated, state.limitReached, state.byType);
                return new ScreenshotResult(png, state.blocked, state.limitReached);
            } catch (PlaywrightException exception) {
                if (state.failure != null) throw state.failure;
                throw new ScreenshotException(exception instanceof TimeoutError ? TIMEOUT : ENGINE_UNAVAILABLE);
            }
        } catch (IOException | PlaywrightException exception) {
            // 不保留原始异常链，浏览器异常可能含完整 URL 或敏感查询参数。
            throw new ScreenshotException(ENGINE_UNAVAILABLE);
        }
    }

    private void handleResource(Route route, URI entry, ScreenshotBudget budget, State state) {
        Request request = route.request();
        boolean main = request.isNavigationRequest() && request.frame().parentFrame() == null;
        String type = request.resourceType();
        // 类型来自浏览器固定分类；日志不包含目标 URL 或网页内容。
        String safeType = Set.of("document", "stylesheet", "image", "font", "script", "media", "xhr", "fetch").contains(type) ? type : "other";
        state.observed++;
        state.byType.merge(safeType, 1, Integer::sum);
        try {
            if (state.failure != null) { route.abort(); return; }
            budget.remainingMillis();
            if (state.limitReached && !main) {
                state.blocked++;
                state.truncated++;
                route.abort();
                return;
            }
            budget.requestStarted();
            URI target = policy.requireResource(request.url());
            if (!request.method().equals("GET") || !Set.of("document", "stylesheet", "image", "font").contains(type)
                    || (type.equals("document") && (!main || state.mainServed || !policy.samePage(entry, target)))) {
                if (main) throw new ScreenshotException(PAGE_FETCH_FAILED);
                state.blocked++;
                route.abort();
                return;
            }
            ScreenshotResource resource = fetcher.fetch(target, type, budget);
            route.fulfill(new Route.FulfillOptions().setStatus(200).setBodyBytes(resource.bytes())
                    .setContentType(resource.contentType()).setHeaders(resource.headers()));
            state.fetched++;
            if (main) state.mainServed = true;
        } catch (ScreenshotException exception) {
            state.blocked++;
            boolean requestLimit = exception.kind() == LIMIT_EXCEEDED && exception.diagnostic() != null
                    && exception.diagnostic().reason() == ScreenshotException.DiagnosticReason.REQUEST_COUNT;
            if (!main && state.mainServed && requestLimit) {
                // 主 HTML 已成功，后续请求不再获取；其余大小/超时限制仍是硬失败。
                state.limitReached = true;
                state.truncated++;
            } else if (main || exception.kind() == TIMEOUT || exception.kind() == LIMIT_EXCEEDED) {
                state.failure = exception;
            }
            route.abort();
        }
    }

    private static final class State {
        boolean mainServed;
        int blocked;
        int observed;
        int fetched;
        int truncated;
        boolean limitReached;
        final Map<String, Integer> byType = new TreeMap<>();
        ScreenshotException failure;
    }
}
