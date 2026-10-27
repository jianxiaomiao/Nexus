package com.nexus.screenshot.service;

import com.nexus.NexusServerApplication;
import com.nexus.screenshot.exception.ScreenshotException;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static com.nexus.screenshot.exception.ScreenshotException.Kind.INVALID_REQUEST;

/**
 * Experiment-only launcher. It is compiled into target/test-classes and is not
 * packaged in the production JAR.
 *
 * <p>The production ScreenshotRenderer, Playwright lifecycle, execution Gate,
 * scheduler, persistence, quota and HTTP chain remain unchanged. Only the
 * resource fetcher is replaced with a deterministic in-memory source so a
 * controlled load never contacts CSDN, Zhihu or another external site.</p>
 */
public final class ControlledScreenshotExperimentApplication {
    static final URI FIXTURE_URI = URI.create(
            "https://blog.csdn.net/nexus-controlled-screenshot-experiment");
    private static final String DELAY_ENV =
            "NEXUS_EXPERIMENT_SCREENSHOT_FIXTURE_DELAY_MS";

    private ControlledScreenshotExperimentApplication() {
    }

    public static void main(String[] args) {
        long delayMillis = requireDelayMillis(System.getenv(DELAY_ENV));
        SpringApplication application = new SpringApplication(NexusServerApplication.class);
        application.addInitializers(context -> registerControlledFetcher(context, delayMillis));
        application.run(args);
    }

    private static void registerControlledFetcher(
            ConfigurableApplicationContext applicationContext, long delayMillis) {
        if (!(applicationContext instanceof GenericApplicationContext context)) {
            throw new IllegalStateException(
                    "Controlled screenshot experiment requires GenericApplicationContext");
        }
        context.registerBean(
                "controlledScreenshotExperimentResourceFetcher",
                ScreenshotResourceFetcher.class,
                () -> new ControlledResourceFetcher(
                        context.getBean(ScreenshotUrlPolicy.class), delayMillis),
                definition -> definition.setPrimary(true));
    }

    static long requireDelayMillis(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return 2_000L;
        }
        long value;
        try {
            value = Long.parseLong(rawValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(DELAY_ENV + " must be an integer", exception);
        }
        if (value < 500L || value > 5_000L) {
            throw new IllegalArgumentException(DELAY_ENV + " must be between 500 and 5000");
        }
        return value;
    }

    static final class ControlledResourceFetcher extends ScreenshotResourceFetcher {
        private static final Logger log =
                LoggerFactory.getLogger(ControlledResourceFetcher.class);
        private static final byte[] HTML = fixtureHtml().getBytes(StandardCharsets.UTF_8);

        private final long delayMillis;

        ControlledResourceFetcher(ScreenshotUrlPolicy policy, long delayMillis) {
            // The parent requires a CloseableHttpClient, but fetch() is overridden
            // and this client is never allowed to make a request.
            super(policy, HttpClients.createMinimal());
            this.delayMillis = delayMillis;
            log.info("CONTROLLED_SCREENSHOT_EXPERIMENT_FETCHER_ACTIVE fixtureDelayMs={}",
                    delayMillis);
        }

        @Override
        public ScreenshotResource fetch(
                URI uri, String resourceType, ScreenshotBudget budget) {
            if (!FIXTURE_URI.equals(uri) || !"document".equals(resourceType)) {
                throw new ScreenshotException(INVALID_REQUEST);
            }
            sleepWithinBudget(budget);
            return new ScreenshotResource(
                    HTML.clone(), "text/html; charset=UTF-8", Map.of());
        }

        private void sleepWithinBudget(ScreenshotBudget budget) {
            long remainingDelay = delayMillis;
            while (remainingDelay > 0L) {
                budget.remainingMillis();
                long slice = Math.min(remainingDelay, 100L);
                try {
                    Thread.sleep(Duration.ofMillis(slice));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ScreenshotException(
                            ScreenshotException.Kind.ENGINE_UNAVAILABLE);
                }
                remainingDelay -= slice;
            }
            budget.remainingMillis();
        }

        private static String fixtureHtml() {
            StringBuilder cards = new StringBuilder();
            for (int index = 1; index <= 48; index++) {
                cards.append("<article><span>")
                        .append(String.format("%02d", index))
                        .append("</span><strong>Nexus</strong><small>controlled workload</small></article>");
            }
            return """
                    <!doctype html>
                    <html lang="en">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width,initial-scale=1">
                      <title>Nexus controlled screenshot experiment</title>
                      <style>
                        *{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;font-family:Arial,sans-serif}
                        body{color:#f8fafc;background:linear-gradient(135deg,#07111f,#12304a 55%,#075985);padding:36px}
                        h1{font-size:42px;margin:0 0 8px}.meta{color:#bae6fd;margin-bottom:24px}
                        main{display:grid;grid-template-columns:repeat(8,1fr);gap:10px}
                        article{height:82px;padding:12px;border:1px solid rgba(255,255,255,.22);border-radius:12px;
                          background:linear-gradient(145deg,rgba(255,255,255,.16),rgba(255,255,255,.04));
                          box-shadow:0 8px 20px rgba(0,0,0,.18)}
                        span{display:block;color:#7dd3fc;font-size:12px}strong{display:block;margin:6px 0 2px}
                        small{color:#cbd5e1;font-size:10px}
                      </style>
                    </head>
                    <body>
                      <h1>Nexus Backpressure</h1>
                      <div class="meta">Fixed offline HTML · JavaScript disabled · 1280 × 720</div>
                      <main>__CARDS__</main>
                    </body>
                    </html>
                    """.replace("__CARDS__", cards.toString());
        }
    }
}
