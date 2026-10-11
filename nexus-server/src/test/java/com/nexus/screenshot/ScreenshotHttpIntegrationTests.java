package com.nexus.screenshot;

import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.credential.GeneratedApiKey;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.screenshot.service.ScreenshotResource;
import com.nexus.screenshot.service.ScreenshotResourceFetcher;
import com.nexus.usage.UsageTestCleanup;
import com.nexus.usage.mapper.UsageEventMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScreenshotHttpIntegrationTests {
    private static final String SECRET = Base64.getEncoder().encodeToString(SecureRandom.getSeed(32));
    @DynamicPropertySource static void jwt(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> SECRET);
        registry.add("nexus.auth.jwt.issuer", () -> "nexus");
        registry.add("nexus.auth.jwt.access-token-ttl", () -> "30m");
    }
    @Value("${local.server.port}") int port;
    @Autowired ApiKeyCredentialGenerator generator;
    @Autowired UserMapper users;
    @Autowired ApplicationMapper applications;
    @Autowired ApiKeyMapper keys;
    @Autowired UsageEventMapper events;
    @Autowired @Qualifier("usageExecutor") Executor usageExecutor;
    @MockitoBean ScreenshotResourceFetcher fetcher;
    private User owner;
    private Application application;
    private ApiKey key;
    private GeneratedApiKey credential;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void createFreshKey() {
        owner = new User(); owner.setEmail("screenshot-" + UUID.randomUUID() + "@example.test");
        owner.setDisplayName("Screenshot integration"); owner.setPasswordHash("test-only-password-hash"); users.insert(owner);
        application = new Application(); application.setOwnerUserId(owner.getId()); application.setName("screenshot-" + UUID.randomUUID()); applications.insert(application);
        credential = generator.generate(); key = new ApiKey(); key.setApplicationId(application.getId()); key.setName("screenshot integration");
        key.setPublicId(credential.publicId()); key.setSecretHash(credential.secretHash()); key.setKeyPreview(credential.keyPreview()); keys.insert(key);
        when(fetcher.fetch(any(), eq("document"), any())).thenReturn(new ScreenshotResource(
                "<html><head><link rel='icon' href='data:,'></head><body style='background:#22aa66'>Static screenshot</body></html>".getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"));
    }
    @AfterEach void cleanup() throws InterruptedException {
        if (key != null && key.getId() != null) { UsageTestCleanup.deleteEventsAfterPendingWrites(usageExecutor, events, key.getId()); keys.deleteById(key.getId()); }
        if (application != null && application.getId() != null) applications.deleteById(application.getId());
        if (owner != null && owner.getId() != null) users.deleteById(owner.getId());
    }
    @Test void realMachineAuthenticationReturnsPngAndRecordsUsage() throws Exception {
        var response = request("{\"url\":\"https://blog.csdn.net/article\"}", credential.plaintextKey());
        assertEquals(200, response.statusCode()); assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("false", response.headers().firstValue("X-Nexus-Resource-Limit-Reached").orElseThrow());
        var png = ImageIO.read(new ByteArrayInputStream(response.body()));
        assertEquals(1280, png.getWidth()); assertEquals(720, png.getHeight());
        awaitEvent(200);
    }
    @Test void partialScreenshotIs200WithQuotaHeaderAndSuccessfulUsage() throws Exception {
        StringBuilder html = new StringBuilder("<html><head><link rel='icon' href='data:,'></head><body>");
        for (int index = 0; index < 70; index++) html.append("<img src='https://unlisted.invalid/image-").append(index).append(".png'>");
        html.append("</body></html>");
        when(fetcher.fetch(any(), eq("document"), any())).thenReturn(new ScreenshotResource(
                html.toString().getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"));
        var response = request("{\"url\":\"https://blog.csdn.net/article\"}", credential.plaintextKey());
        assertEquals(200, response.statusCode());
        assertEquals("true", response.headers().firstValue("X-Nexus-Resource-Limit-Reached").orElseThrow());
        assertEquals("70", response.headers().firstValue("X-Nexus-Blocked-Resources").orElseThrow());
        assertNotNull(ImageIO.read(new ByteArrayInputStream(response.body())));
        verify(fetcher, times(1)).fetch(any(), anyString(), any());
        awaitEvent(200);
    }
    @Test void filterRejectsInvalidOrDisabledKeyBeforeBrowserAndBodyValidation() throws Exception {
        assertEquals(401, request("not-json", "not-a-valid-key").statusCode());
        key.setStatus(1); keys.updateById(key);
        assertEquals(403, request("not-json", credential.plaintextKey()).statusCode());
        verifyNoInteractions(fetcher);
        assertEquals(0, events.selectCount(new QueryWrapper<com.nexus.usage.entity.UsageEvent>().eq("api_key_id", key.getId())));
    }
    @Test void validKeyWithInvalidTargetReturnsJson400AndRecordsControllerFailure() throws Exception {
        var response = request("{\"url\":\"https://127.0.0.1/private\"}", credential.plaintextKey());
        assertEquals(400, response.statusCode()); assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("INVALID_SCREENSHOT_REQUEST"));
        verifyNoInteractions(fetcher);
        awaitEvent(400);
    }
    private HttpResponse<byte[]> request(String body, String plaintextKey) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/web/screenshot"))
                .header("Authorization", "ApiKey " + plaintextKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private void awaitEvent(int status) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            var rows = events.selectList(new QueryWrapper<com.nexus.usage.entity.UsageEvent>().eq("api_key_id", key.getId()));
            if (!rows.isEmpty()) { assertEquals(1, rows.size()); assertEquals("web.screenshot", rows.getFirst().getApiCode()); assertEquals(status, rows.getFirst().getHttpStatusCode()); return; }
            Thread.sleep(50);
        }
        fail("Screenshot Usage event was not written");
    }
}
