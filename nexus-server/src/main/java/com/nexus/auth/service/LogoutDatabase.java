package com.nexus.auth.service;

import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.token.AccessTokenIdentity;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 退出使用同一数据库的独立短连接，避免主池的30秒等待吞掉8秒转发预算。
 * 仅此路径设置建连350ms、网络读取700ms、锁等待1秒；不改其他业务的数据源超时。
 */
@Component
public class LogoutDatabase {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public LogoutDatabase(DataSource mainDataSource, Clock clock) {
        if (!(mainDataSource instanceof HikariDataSource main)) {
            throw new IllegalArgumentException("退出短连接需要当前Hikari数据源配置");
        }
        String url = main.getJdbcUrl();
        int queryStart = url.indexOf('?');
        String base = queryStart < 0 ? url : url.substring(0, queryStart);
        String query = queryStart < 0 ? "" : Arrays.stream(url.substring(queryStart + 1).split("&"))
                .filter(part -> !part.regionMatches(true, 0, "connectTimeout=", 0, 15)
                        && !part.regionMatches(true, 0, "socketTimeout=", 0, 14))
                .collect(Collectors.joining("&"));
        String boundedUrl = base + "?" + (query.isEmpty() ? "" : query + "&") + "connectTimeout=350&socketTimeout=700";
        DriverManagerDataSource shortConnections = new DriverManagerDataSource();
        shortConnections.setUrl(boundedUrl);
        shortConnections.setUsername(main.getUsername());
        shortConnections.setPassword(main.getPassword());
        Properties properties = new Properties();
        properties.putAll(main.getDataSourceProperties());
        properties.setProperty("connectTimeout", "350");
        properties.setProperty("socketTimeout", "700");
        shortConnections.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(shortConnections);
        jdbc.setQueryTimeout(1);
        transactions = new TransactionTemplate(new JdbcTransactionManager(shortConnections));
        transactions.setTimeout(1);
        this.clock = clock;
    }

    public void revoke(AccessTokenIdentity identity) {
        AuthSessionTransactions.requireNoOuterTransaction();
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
            // 与Mapper路径采用同一User→Session顺序，不因专用连接绕开互斥协议。
            List<Long> users = jdbc.query("SELECT id FROM users WHERE id=? FOR UPDATE",
                    (rs, row) -> rs.getLong(1), identity.userId());
            if (users.isEmpty()) return;
            List<SessionRow> sessions = jdbc.query(
                    "SELECT user_id,expires_at,revoked_at FROM auth_sessions WHERE id=? FOR UPDATE",
                    (rs, row) -> new SessionRow(rs.getLong("user_id"),
                            rs.getObject("expires_at", LocalDateTime.class),
                            rs.getObject("revoked_at", LocalDateTime.class)), identity.sessionId());
            // 已清理的可信目标与已撤销/到期目标均为幂等完成。
            if (sessions.isEmpty()) return;
            SessionRow session = sessions.getFirst();
            if (session.userId() != identity.userId()) throw new InvalidAccessTokenException();
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
            if (session.revokedAt() != null || !now.isBefore(session.expiresAt())) return;
            jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE id=? AND revoked_at IS NULL", now, identity.sessionId());
        });
    }

    public AccessTokenIdentity locateRefresh(String hash) {
        AuthSessionTransactions.requireNoOuterTransaction();
        List<AccessTokenIdentity> identities = jdbc.query(
                "SELECT s.id,s.user_id FROM refresh_tokens r JOIN auth_sessions s ON s.id=r.session_id WHERE r.token_hash=?",
                (rs, row) -> new AccessTokenIdentity(rs.getLong("user_id"), rs.getLong("id")), hash);
        return identities.isEmpty() ? null : identities.getFirst();
    }

    /** A consumed RT remains a logout proof; it never mints another credential. */
    public void revokeRefresh(AccessTokenIdentity identity, String hash) {
        AuthSessionTransactions.requireNoOuterTransaction();
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET SESSION innodb_lock_wait_timeout=1");
            if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, identity.userId()).isEmpty()) return;
            List<Long> owners = jdbc.queryForList("SELECT user_id FROM auth_sessions WHERE id=? FOR UPDATE", Long.class, identity.sessionId());
            if (owners.isEmpty()) return;
            if (owners.getFirst() != identity.userId()) throw new com.nexus.auth.exception.InvalidRefreshTokenException();
            if (jdbc.queryForList("SELECT id FROM refresh_tokens WHERE session_id=? AND token_hash=? FOR UPDATE",
                    Long.class, identity.sessionId(), hash).isEmpty()) throw new com.nexus.auth.exception.InvalidRefreshTokenException();
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
            jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE id=? AND revoked_at IS NULL AND expires_at>?",
                    now, identity.sessionId(), now);
        });
    }

    private record SessionRow(long userId, LocalDateTime expiresAt, LocalDateTime revokedAt) { }
}
