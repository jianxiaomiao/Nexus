package com.nexus.usage.cache;

import com.nexus.usage.dto.UsageQueryResponse;
import com.nexus.usage.exception.UsageSummaryUnavailableException;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SetArgs;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.cfg.DateTimeFeature;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(name = "nexus.usage.cache.enabled", havingValue = "true")
public class UsageSummaryRedisCache implements AutoCloseable {
    private static final Duration MAX_TTL = Duration.ofSeconds(5);
    private static final long MAX_TTL_JITTER_MS = 1_000;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final long REFILL_WAIT_MS = 500;

    private final RedisClient client;
    private final ScheduledExecutorService connector;
    private final ObjectReader reader;
    private final ObjectWriter writer;
    private final String keyPrefix;
    private final int timeoutMs;
    private final Counter hits;
    private final Counter misses;
    private final Counter readErrors;
    private final Counter writeErrors;
    private final Counter loads;
    private final Counter joined;
    private final Counter waitTimeouts;
    private final Counter loadRejections;
    private final Semaphore loadPermits;
    private final ConcurrentHashMap<Key, CompletableFuture<Refill>> refills = new ConcurrentHashMap<>();
    private volatile StatefulRedisConnection<String, String> connection;

    public UsageSummaryRedisCache(ObjectMapper json, MeterRegistry registry,
                                  @Value("${nexus.usage.cache.redis-uri:redis://127.0.0.1:6379}") String uri,
                                  @Value("${nexus.usage.cache.namespace:local}") String namespace,
                                  @Value("${nexus.usage.cache.timeout-ms:25}") int timeoutMs,
                                  @Value("${nexus.usage.cache.max-concurrent-loads:4}") int maxConcurrentLoads) {
        if (!namespace.matches("[A-Za-z0-9_-]{1,48}") || timeoutMs < 1 || timeoutMs > 100) {
            throw new IllegalArgumentException("Invalid Usage Redis namespace or command timeout");
        }
        if (maxConcurrentLoads < 1) {
            throw new IllegalArgumentException("Usage cache max-concurrent-loads must be positive");
        }
        RedisURI endpoint;
        try {
            endpoint = RedisURI.create(uri);
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalArgumentException("Invalid Usage Redis URI");
        }
        endpoint.setTimeout(CONNECT_TIMEOUT);
        client = RedisClient.create(endpoint);
        client.setOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .requestQueueSize(256)
                .socketOptions(SocketOptions.builder().connectTimeout(CONNECT_TIMEOUT).build())
                .build());
        reader = json.readerFor(UsageQueryResponse.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        writer = json.writerFor(UsageQueryResponse.class)
                .without(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .without(DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE);
        this.keyPrefix = "nexus:" + namespace + ":usage-summary:v1:";
        this.timeoutMs = timeoutMs;
        hits = registry.counter("nexus.usage.cache.reads", "outcome", "hit");
        misses = registry.counter("nexus.usage.cache.reads", "outcome", "miss");
        readErrors = registry.counter("nexus.usage.cache.reads", "outcome", "error");
        writeErrors = registry.counter("nexus.usage.cache.writes", "outcome", "error");
        loads = registry.counter("nexus.usage.cache.loads");
        joined = registry.counter("nexus.usage.cache.joined");
        waitTimeouts = registry.counter("nexus.usage.cache.wait.timeouts");
        loadRejections = registry.counter("nexus.usage.cache.load.rejections");
        loadPermits = new Semaphore(maxConcurrentLoads);
        registry.gauge("nexus.usage.cache.load.limit", this, cache -> maxConcurrentLoads);
        registry.gauge("nexus.usage.cache.load.active", this,
                cache -> maxConcurrentLoads - cache.loadPermits.availablePermits());
        connector = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("usage-redis-connect").factory());
        connector.scheduleWithFixedDelay(this::connect, 0, 1, TimeUnit.SECONDS);
    }

    // Authorization must finish before entering the cache, with no SQL transaction held while waiting.
    public UsageQueryResponse getOrLoad(Key key, Instant snapshotStartedAt, Supplier<UsageQueryResponse> load) {
        String redisKey = keyPrefix + key.suffix();
        var current = connection;
        if (current != null && current.isOpen()) {
            try {
                UsageQueryResponse hit = readCached(current, key, redisKey);
                if (hit != null) {
                    hits.increment();
                    return hit;
                }
                misses.increment();
            } catch (RedisException | JacksonException unavailable) {
                readErrors.increment();
                current = null;
            }
        } else {
            readErrors.increment();
            current = null;
        }

        var candidate = new CompletableFuture<Refill>();
        var running = refills.putIfAbsent(key, candidate);
        if (running != null) {
            joined.increment();
            return waitForRefill(running, key, redisKey);
        }

        try {
            Refill refill = refill(current, key, redisKey, snapshotStartedAt, load);
            candidate.complete(refill);
            return refill.response();
        } catch (RuntimeException | Error failed) {
            candidate.completeExceptionally(failed);
            throw failed;
        } finally {
            refills.remove(key, candidate);
        }
    }

    private Refill refill(StatefulRedisConnection<String, String> current, Key key, String redisKey,
                          Instant snapshotStartedAt, Supplier<UsageQueryResponse> load) {
        // A previous refill may have completed between the initial miss and the map insertion.
        if (current != null) {
            try {
                UsageQueryResponse hit = readCached(current, key, redisKey);
                if (hit != null) {
                    return new Refill(hit, null);
                }
            } catch (RedisException | JacksonException unavailable) {
                current = null;
            }
        }

        UsageQueryResponse result = loadFromMySql(load);
        // Jitter only shortens the freshness window, measured from the start of the snapshot.
        Instant expiresAt = snapshotStartedAt.plus(MAX_TTL)
                .minusMillis(ThreadLocalRandom.current().nextLong(MAX_TTL_JITTER_MS + 1));
        if (current != null && expiresAt.isAfter(Instant.now())) {
            try {
                await(current.async().set(redisKey, writer.writeValueAsString(result),
                        SetArgs.Builder.pxAt(expiresAt.toEpochMilli()).nx()));
            } catch (RedisException | JacksonException unavailable) {
                writeErrors.increment();
            }
        }
        return new Refill(result, expiresAt);
    }

    private UsageQueryResponse loadFromMySql(Supplier<UsageQueryResponse> load) {
        if (!loadPermits.tryAcquire()) {
            loadRejections.increment();
            throw new UsageSummaryUnavailableException();
        }
        try {
            loads.increment();
            return load.get();
        } finally {
            loadPermits.release();
        }
    }

    private UsageQueryResponse waitForRefill(CompletableFuture<Refill> running, Key key, String redisKey) {
        Refill result;
        try {
            result = running.get(REFILL_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UsageSummaryUnavailableException();
        } catch (TimeoutException timedOut) {
            waitTimeouts.increment();
            throw new UsageSummaryUnavailableException();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new UsageSummaryUnavailableException();
        }
        if (result.expiresAt() != null) {
            if (Instant.now().isBefore(result.expiresAt())) {
                return result.response();
            }
            throw new UsageSummaryUnavailableException();
        }

        // Redis supplied this value during the double-check. Let Redis enforce its original expiry.
        var current = connection;
        if (current != null && current.isOpen()) {
            try {
                UsageQueryResponse hit = readCached(current, key, redisKey);
                if (hit != null) {
                    return hit;
                }
            } catch (RedisException | JacksonException unavailable) {
                throw new UsageSummaryUnavailableException();
            }
        }
        throw new UsageSummaryUnavailableException();
    }

    private UsageQueryResponse readCached(StatefulRedisConnection<String, String> current, Key key, String redisKey) {
        String value = await(current.async().get(redisKey));
        if (value == null) {
            return null;
        }
        UsageQueryResponse response = reader.readValue(value);
        if (response == null || response.periodStart() == null || response.periodEndExclusive() == null
                || response.dailyCounts() == null || response.apiCounts() == null
                || !response.periodStart().toInstant().equals(key.startUtc().toInstant(ZoneOffset.UTC))
                || !response.periodEndExclusive().toInstant().equals(key.endUtc().toInstant(ZoneOffset.UTC))) {
            throw new RedisException("Invalid cached Usage period");
        }
        return response;
    }

    private record Refill(UsageQueryResponse response, Instant expiresAt) {
    }

    private void connect() {
        try {
            connection = client.connect();
            connector.shutdown(); // Lettuce reconnects this shared connection after transport failures.
        } catch (RedisException unavailable) {
            // MySQL remains available while the next background attempt is pending.
        }
    }

    private <T> T await(RedisFuture<T> future) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new RedisException(interrupted);
        } catch (TimeoutException timedOut) {
            future.cancel(false);
            throw new RedisException(timedOut);
        } catch (ExecutionException failed) {
            throw new RedisException(failed);
        }
    }

    @Override
    public void close() {
        connector.shutdownNow();
        client.shutdown();
    }

    public record Key(long ownerId, long applicationId, long apiKeyId, LocalDateTime startUtc,
                      LocalDateTime endUtc) {
        private String suffix() {
            return ownerId + ":" + applicationId + ":" + apiKeyId + ":" + startUtc + ":" + endUtc;
        }
    }
}
