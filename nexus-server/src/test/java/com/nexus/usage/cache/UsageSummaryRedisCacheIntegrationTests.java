package com.nexus.usage.cache;

import com.nexus.usage.dto.UsageQueryResponse;
import com.nexus.usage.exception.UsageSummaryUnavailableException;
import io.lettuce.core.RedisClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_REDIS_URI", matches = "redis://(localhost|127\\.0\\.0\\.1):[0-9]+")
class UsageSummaryRedisCacheIntegrationTests {
    @Test
    void expiryAndMalformedValuesReloadWithoutChangingBeijingOffsets() throws InterruptedException {
        String uri = System.getenv("NEXUS_TEST_REDIS_URI");
        String namespace = "test-" + UUID.randomUUID();
        var meters = new SimpleMeterRegistry();
        var key = new UsageSummaryRedisCache.Key(1, 2, 3,
                LocalDateTime.parse("2026-11-24T16:00:00"), LocalDateTime.parse("2026-11-25T16:00:00"));
        var otherOwner = new UsageSummaryRedisCache.Key(4, 2, 3, key.startUtc(), key.endUtc());
        var loads = new AtomicInteger();
        var client = RedisClient.create(uri);
        try (var cache = new UsageSummaryRedisCache(new ObjectMapper(), meters, uri, namespace, 100);
             var redis = client.connect()) {
            waitForConnection(cache);
            var expected = cache.getOrLoad(key, Instant.now().minusSeconds(2), () -> response(key, loads));
            var hit = cache.getOrLoad(key, Instant.now(), () -> response(key, loads));
            assertEquals(expected, hit);
            assertEquals(ZoneOffset.ofHours(8), hit.periodStart().getOffset());
            assertEquals(1, loads.get());
            assertNotEquals(expected, cache.getOrLoad(otherOwner, Instant.now(), () -> response(key, loads)));

            String redisKey = "nexus:" + namespace + ":usage-summary:v1:1:2:3:"
                    + key.startUtc() + ":" + key.endUtc();
            long waitMs = Math.max(0, redis.sync().pttl(redisKey)) + 30;
            assertTrue(waitMs < 3500, "Redis TTL exceeded the remaining snapshot lifetime");
            Thread.sleep(waitMs);
            assertNotEquals(expected, cache.getOrLoad(key, Instant.now(), () -> response(key, loads)));

            redis.sync().setex(redisKey, 1, "bad json");
            assertEquals(4, cache.getOrLoad(key, Instant.now(), () -> response(key, loads)).allTimeCount());
            assertEquals(1, meters.get("nexus.usage.cache.reads").tag("outcome", "hit").counter().count());
            assertEquals(1, meters.get("nexus.usage.cache.reads").tag("outcome", "error").counter().count());
            redis.sync().del(redisKey);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void independentFillsSpreadExpirationWithinTheSnapshotFreshnessWindow() throws InterruptedException {
        String uri = System.getenv("NEXUS_TEST_REDIS_URI");
        String namespace = "test-" + UUID.randomUUID();
        LocalDateTime startUtc = LocalDateTime.parse("2026-11-24T16:00:00");
        LocalDateTime endUtc = startUtc.plusDays(1);
        var client = RedisClient.create(uri);
        try (var cache = new UsageSummaryRedisCache(new ObjectMapper(), new SimpleMeterRegistry(),
                uri, namespace, 100);
             var redis = client.connect()) {
            waitForConnection(cache);
            var snapshotLifetimes = new HashSet<Long>();
            for (int apiKeyId = 1; apiKeyId <= 12; apiKeyId++) {
                var key = new UsageSummaryRedisCache.Key(1, 2, apiKeyId, startUtc, endUtc);
                Instant snapshotStartedAt = Instant.now().minusSeconds(1);
                cache.getOrLoad(key, snapshotStartedAt, () -> response(key, new AtomicInteger()));
                String redisKey = "nexus:" + namespace + ":usage-summary:v1:1:2:" + apiKeyId
                        + ":" + startUtc + ":" + endUtc;
                long lifetime = redis.sync().pexpiretime(redisKey) - snapshotStartedAt.toEpochMilli();
                assertTrue(lifetime >= 4_000 && lifetime <= 5_000, "Unexpected snapshot TTL: " + lifetime);
                snapshotLifetimes.add(lifetime);
            }
            assertTrue(snapshotLifetimes.size() > 1, "Independent fills all used the same snapshot lifetime");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void delayedRefillCannotOverwriteSnapshotFromAnotherClient() throws Exception {
        String uri = System.getenv("NEXUS_TEST_REDIS_URI");
        String namespace = "test-" + UUID.randomUUID();
        var key = new UsageSummaryRedisCache.Key(1, 2, 3,
                LocalDateTime.parse("2026-11-24T16:00:00"), LocalDateTime.parse("2026-11-25T16:00:00"));
        var oldReadStarted = new CountDownLatch(1);
        var finishOldRead = new CountDownLatch(1);
        var meters = new SimpleMeterRegistry();
        try (var cache = new UsageSummaryRedisCache(new ObjectMapper(), meters, uri, namespace, 100);
             var secondCache = new UsageSummaryRedisCache(new ObjectMapper(), meters, uri, namespace, 100);
             var worker = Executors.newSingleThreadExecutor()) {
            waitForConnection(cache);
            waitForConnection(secondCache);
            var delayed = worker.submit(() -> cache.getOrLoad(key, Instant.now(), () -> {
                oldReadStarted.countDown();
                try {
                    assertTrue(finishOldRead.await(3, TimeUnit.SECONDS), "Timed out waiting to finish the old read");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return response(key, new AtomicInteger(0));
            }));
            try {
                assertTrue(oldReadStarted.await(3, TimeUnit.SECONDS), "The old read did not enter its loader");
                var fresh = secondCache.getOrLoad(key, Instant.now(), () -> response(key, new AtomicInteger(1)));
                finishOldRead.countDown();
                assertEquals(1, delayed.get(3, TimeUnit.SECONDS).allTimeCount());
                assertEquals(fresh, cache.getOrLoad(key, Instant.now(), () -> response(key, new AtomicInteger(99))));
                assertEquals(2, meters.get("nexus.usage.cache.reads").tag("outcome", "miss").counter().count());
                assertEquals(1, meters.get("nexus.usage.cache.reads").tag("outcome", "hit").counter().count());
            } finally {
                finishOldRead.countDown();
            }
        }
    }

    @Test
    void returnsMySqlResultWhileConnectionIsUnavailable() {
        var meters = new SimpleMeterRegistry();
        var key = new UsageSummaryRedisCache.Key(1, 2, 3,
                LocalDateTime.parse("2026-11-24T16:00:00"), LocalDateTime.parse("2026-11-25T16:00:00"));
        try (var cache = new UsageSummaryRedisCache(new ObjectMapper(), meters,
                "redis://127.0.0.1:1", "test", 25)) {
            assertEquals(1, cache.getOrLoad(key, Instant.now(), () -> response(key, new AtomicInteger())).allTimeCount());
            assertEquals(1, meters.get("nexus.usage.cache.reads").tag("outcome", "error").counter().count());
        }
    }

    @Test
    void coalescesSameKeyDuringAnOutageWithoutBlockingAnotherKey() throws Exception {
        var meters = new SimpleMeterRegistry();
        var start = LocalDateTime.parse("2026-11-24T16:00:00");
        var key = new UsageSummaryRedisCache.Key(1, 2, 3, start, start.plusDays(1));
        var otherKey = new UsageSummaryRedisCache.Key(1, 2, 4, start, start.plusDays(1));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var expected = response(key, new AtomicInteger());
        try (var cache = new UsageSummaryRedisCache(new ObjectMapper(), meters,
                "redis://127.0.0.1:1", "test", 25);
             var pool = Executors.newFixedThreadPool(6)) {
            var leader = pool.submit(() -> cache.getOrLoad(key, Instant.now(), () -> {
                entered.countDown();
                awaitRelease(release);
                return expected;
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var followers = new ArrayList<java.util.concurrent.Future<UsageQueryResponse>>();
                for (int index = 0; index < 4; index++) {
                    followers.add(pool.submit(() -> cache.getOrLoad(key, Instant.now(), () -> {
                        throw new AssertionError("Follower must not query MySQL");
                    })));
                }
                awaitJoins(meters, 4);
                var unrelated = pool.submit(() -> cache.getOrLoad(otherKey, Instant.now(),
                        () -> response(otherKey, new AtomicInteger(10))));
                assertEquals(11, unrelated.get(300, TimeUnit.MILLISECONDS).allTimeCount());
                release.countDown();
                assertSame(expected, leader.get(2, TimeUnit.SECONDS));
                for (var follower : followers) {
                    assertSame(expected, follower.get(2, TimeUnit.SECONDS));
                }
                assertEquals(2, meters.get("nexus.usage.cache.loads").counter().count());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void timeoutKeepsTheLeaderAndFailedFillAllowsALaterRetry() throws Exception {
        var meters = new SimpleMeterRegistry();
        var start = LocalDateTime.parse("2026-11-24T16:00:00");
        var key = new UsageSummaryRedisCache.Key(1, 2, 3, start, start.plusDays(1));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new IllegalStateException("test database failure");
        try (var cache = new UsageSummaryRedisCache(new ObjectMapper(), meters,
                "redis://127.0.0.1:1", "test", 25);
             var pool = Executors.newFixedThreadPool(2)) {
            var leader = pool.submit(() -> cache.getOrLoad(key, Instant.now(), () -> {
                entered.countDown();
                awaitRelease(release);
                throw failure;
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertThrows(UsageSummaryUnavailableException.class, () -> cache.getOrLoad(key, Instant.now(),
                        () -> { throw new AssertionError("Timed-out waiter must not query MySQL"); }));
                var follower = pool.submit(() -> cache.getOrLoad(key, Instant.now(),
                        () -> { throw new AssertionError("Timed-out waiter removed the leader"); }));
                awaitJoins(meters, 2);
                release.countDown();
                assertSame(failure, assertThrows(ExecutionException.class,
                        () -> leader.get(2, TimeUnit.SECONDS)).getCause());
                assertSame(failure, assertThrows(ExecutionException.class,
                        () -> follower.get(2, TimeUnit.SECONDS)).getCause());
                assertEquals(1, meters.get("nexus.usage.cache.wait.timeouts").counter().count());
                assertEquals(1, cache.getOrLoad(key, Instant.now(), () -> response(key, new AtomicInteger())).allTimeCount());
                assertEquals(2, meters.get("nexus.usage.cache.loads").counter().count());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void snapshotBeyondFiveSecondsIsNeitherPublishedNorReused() throws Exception {
        String uri = System.getenv("NEXUS_TEST_REDIS_URI");
        String namespace = "test-" + UUID.randomUUID();
        var meters = new SimpleMeterRegistry();
        var start = LocalDateTime.parse("2026-11-24T16:00:00");
        var key = new UsageSummaryRedisCache.Key(1, 2, 3, start, start.plusDays(1));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var client = RedisClient.create(uri);
        try (var cache = new UsageSummaryRedisCache(new ObjectMapper(), meters, uri, namespace, 100);
             var redis = client.connect();
             var pool = Executors.newFixedThreadPool(2)) {
            waitForConnection(cache);
            var leader = pool.submit(() -> cache.getOrLoad(key, Instant.now().minusSeconds(6), () -> {
                entered.countDown();
                awaitRelease(release);
                return response(key, new AtomicInteger());
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var follower = pool.submit(() -> cache.getOrLoad(key, Instant.now(),
                        () -> { throw new AssertionError("Follower must not query MySQL"); }));
                awaitJoins(meters, 1);
                release.countDown();
                assertEquals(1, leader.get(2, TimeUnit.SECONDS).allTimeCount());
                assertTrue(assertThrows(ExecutionException.class,
                        () -> follower.get(2, TimeUnit.SECONDS)).getCause() instanceof UsageSummaryUnavailableException);
                String redisKey = "nexus:" + namespace + ":usage-summary:v1:1:2:3:" + start + ":" + start.plusDays(1);
                assertEquals(0L, redis.sync().exists(redisKey));
            } finally {
                release.countDown();
            }
        } finally {
            client.shutdown();
        }
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS), "Test never released the loader");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitJoins(SimpleMeterRegistry meters, int expected) throws InterruptedException {
        var joined = meters.get("nexus.usage.cache.joined").counter();
        Instant until = Instant.now().plusSeconds(2);
        while (joined.count() < expected && Instant.now().isBefore(until)) {
            Thread.sleep(5);
        }
        assertEquals(expected, joined.count());
    }

    private static void waitForConnection(UsageSummaryRedisCache cache) throws InterruptedException {
        Instant waitUntil = Instant.now().plusSeconds(4);
        while (ReflectionTestUtils.getField(cache, "connection") == null && Instant.now().isBefore(waitUntil)) {
            Thread.sleep(20);
        }
        assertTrue(ReflectionTestUtils.getField(cache, "connection") != null, "Redis adapter did not connect");
    }

    private static UsageQueryResponse response(UsageSummaryRedisCache.Key key, AtomicInteger loads) {
        ZoneOffset beijing = ZoneOffset.ofHours(8);
        return new UsageQueryResponse(loads.incrementAndGet(), 0, "Asia/Shanghai",
                OffsetDateTime.of(key.startUtc(), ZoneOffset.UTC).withOffsetSameInstant(beijing),
                OffsetDateTime.of(key.endUtc(), ZoneOffset.UTC).withOffsetSameInstant(beijing), List.of(), List.of());
    }
}
