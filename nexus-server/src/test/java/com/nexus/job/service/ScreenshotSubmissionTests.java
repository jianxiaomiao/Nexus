package com.nexus.job.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.exception.ScreenshotSubmissionException;
import com.nexus.job.mapper.ScreenshotJobMapper;
import com.nexus.job.model.ScreenshotSubmissionKey;
import com.nexus.job.store.ScreenshotResultStore;
import com.nexus.quota.service.ApplicationQuotaService;
import com.nexus.screenshot.service.ScreenshotUrlPolicy;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScreenshotSubmissionTests {
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");
    private static final String KEY = NOW.toEpochMilli() + ".123e4567-e89b-42d3-a456-426614174000";
    private static final String URL = "https://blog.csdn.net/article";

    @Test void expiredFutureMalformedAndLegacyKeysHaveExplicitSemantics() {
        assertNull(ScreenshotSubmissionKey.parse(null, NOW));
        assertEquals(NOW.plus(Duration.ofDays(7)), ScreenshotSubmissionKey.parse(KEY, NOW).expiresAt());
        assertEquals(ScreenshotSubmissionException.Kind.EXPIRED,
                assertThrows(ScreenshotSubmissionException.class, () -> ScreenshotSubmissionKey.parse(KEY, NOW.plus(Duration.ofDays(7)))).kind());
        assertEquals(ScreenshotSubmissionException.Kind.INVALID,
                assertThrows(ScreenshotSubmissionException.class, () -> ScreenshotSubmissionKey.parse(KEY, NOW.minusSeconds(61))).kind());
        assertThrows(ScreenshotSubmissionException.class, () -> ScreenshotSubmissionKey.parse("invalid", NOW));
    }

    @Test @SuppressWarnings("unchecked") void concurrentRetriesCreateAndDebitOnceAndRejectChangedPayload() throws Exception {
        var jobs = mock(ScreenshotJobMapper.class);
        var quotas = mock(ApplicationQuotaService.class);
        var info = new MapperBuilderAssistant(new MybatisConfiguration(), "submission-tests");
        TableInfoHelper.initTableInfo(info, ScreenshotJob.class);
        var stored = new AtomicReference<ScreenshotJob>();
        when(jobs.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(jobs.insert(any(ScreenshotJob.class))).thenAnswer(invocation -> { stored.set(invocation.getArgument(0)); return 1; });
        when(quotas.consumeOnceAndCommit(any(), any())).thenAnswer(invocation -> ((Supplier<ScreenshotJob>) invocation.getArgument(1)).get());
        var service = new ScreenshotJobService(jobs, new ScreenshotUrlPolicy(), quotas,
                mock(ScreenshotResultStore.class), Clock.fixed(NOW, ZoneOffset.UTC));
        var identity = new ApiKeyIdentity(7L, 3L);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<ScreenshotJob>>();
            for (int i = 0; i < 20; i++) futures.add(executor.submit(() -> service.submit(identity, URL, KEY)));
            for (var future : futures) { var completed = future.get(5, TimeUnit.SECONDS); assertEquals(stored.get().getJobId(), completed.getJobId()); }
        }
        verify(jobs, times(1)).insert(any(ScreenshotJob.class));
        verify(quotas, times(1)).consumeOnceAndCommit(any(), any());
        assertEquals(ScreenshotSubmissionException.Kind.CONFLICT,
                assertThrows(ScreenshotSubmissionException.class, () -> service.submit(identity, URL + "-changed", KEY)).kind());
        assertEquals(KEY, stored.get().getSubmissionKey());
        assertEquals(LocalDateTime.ofInstant(NOW.plus(Duration.ofDays(7)), ZoneOffset.UTC), stored.get().getSubmissionExpiresAt());
    }
}
