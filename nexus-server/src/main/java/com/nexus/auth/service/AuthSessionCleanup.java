package com.nexus.auth.service;

import com.nexus.auth.entity.AuthSession;
import com.nexus.auth.mapper.AuthSessionMapper;
import com.nexus.auth.mapper.RefreshTokenMapper;
import com.nexus.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;

@Service
public class AuthSessionCleanup {
    private static final Logger log = LoggerFactory.getLogger(AuthSessionCleanup.class);
    private static final Duration RETENTION = Duration.ofHours(72);
    private final AuthSessionMapper sessions;
    private final RefreshTokenMapper tokens;
    private final UserMapper users;
    private final AuthSessionTransactions transactions;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;

    public AuthSessionCleanup(AuthSessionMapper sessions, RefreshTokenMapper tokens, UserMapper users,
                              AuthSessionTransactions transactions, Clock clock,
                              @Value("${nexus.auth.session.cleanup-enabled:true}") boolean enabled,
                              @Value("${nexus.auth.session.cleanup-batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("会话清理批量须在1到1000之间");
        this.sessions = sessions;
        this.tokens = tokens;
        this.users = users;
        this.transactions = transactions;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${nexus.auth.session.cleanup-cron:0 0 3 * * *}", zone = "UTC", scheduler = "authSessionCleanupScheduler")
    public void scheduledCleanup() {
        if (!enabled) return;
        try {
            // 固定本轮候选截止，避免运行过程中持续追逐新到期的数据。
            LocalDateTime cutoff = cutoff();
            while (cleanupBatch(cutoff) > 0) { /* 每个会话独立短事务 */ }
        } catch (RuntimeException exception) {
            log.error("登录会话清理失败", exception);
        }
    }

    public int cleanupBatch() { return cleanupBatch(cutoff()); }

    private int cleanupBatch(LocalDateTime cutoff) {
        AuthSessionTransactions.requireNoOuterTransaction();
        var candidates = new LinkedHashMap<Long, AuthSession>();
        sessions.selectExpiredCandidates(cutoff, batchSize).forEach(s -> candidates.put(s.getId(), s));
        sessions.selectRevokedCandidates(cutoff, batchSize).forEach(s -> candidates.put(s.getId(), s));
        int deleted = 0;
        for (AuthSession candidate : candidates.values()) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(() -> deleteIfEligible(candidate, cutoff)))) deleted++;
            } catch (RuntimeException exception) {
                log.warn("登录会话清理未完成 sessionId={}", candidate.getId(), exception);
            }
        }
        return deleted;
    }

    private boolean deleteIfEligible(AuthSession candidate, LocalDateTime batchCutoff) {
        if (users.selectForUpdate(candidate.getUserId()) == null) return false;
        AuthSession current = sessions.selectForUpdate(candidate.getId());
        if (current == null || !candidate.getUserId().equals(current.getUserId())) return false;
        LocalDateTime endedAt = current.getExpiresAt();
        if (current.getRevokedAt() != null && current.getRevokedAt().isBefore(endedAt)) endedAt = current.getRevokedAt();
        // 锁后再次读取时钟，时钟回拨也不能提前清理。
        if (endedAt.isAfter(batchCutoff) || endedAt.isAfter(cutoff())) return false;
        tokens.deleteBySessionId(current.getId());
        if (sessions.deleteById(current.getId()) != 1) throw new IllegalStateException("会话清理状态发生变化");
        return true;
    }

    private LocalDateTime cutoff() {
        return LocalDateTime.ofInstant(clock.instant().minus(RETENTION).truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
