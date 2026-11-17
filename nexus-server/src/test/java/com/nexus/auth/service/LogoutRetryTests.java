package com.nexus.auth.service;

import com.nexus.auth.exception.AuthSessionUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LogoutRetryTests {
    @Test void retriesThreeTimesWithTheAgreedBackoffOutsideTransactions() {
        List<Long> waits = new ArrayList<>(); AtomicLong nanos = new AtomicLong(); AtomicInteger attempts = new AtomicInteger();
        LogoutRetry retry = new LogoutRetry(nanos::get, millis -> { waits.add(millis); nanos.addAndGet(millis * 1_000_000); });
        retry.execute(() -> { if (attempts.incrementAndGet() < 4) throw temporary(1213, "40001"); });
        assertEquals(4, attempts.get()); assertEquals(List.of(200L, 400L, 800L), waits);
    }
    @Test void exhaustionPreservesTheFinalCause() {
        AtomicInteger attempts = new AtomicInteger(); var failure = temporary(1205, "HY000");
        LogoutRetry retry = new LogoutRetry(() -> 0L, millis -> { });
        var result = assertThrows(AuthSessionUnavailableException.class, () -> retry.execute(() -> { attempts.incrementAndGet(); throw failure; }));
        assertEquals(4, attempts.get()); assertSame(failure, result.getCause());
    }
    @Test void permanentDatabaseFailuresAreNotRetried() {
        for (SQLException sql : List.of(new SQLException("test", "28000", 1045), new SQLException("test", "42000", 1142),
                new SQLException("test", "23000", 1062), new SQLException("test", "42000", 1064))) {
            AtomicInteger attempts = new AtomicInteger();
            var retry = new LogoutRetry(() -> 0L, millis -> fail("permanent error must not sleep"));
            assertThrows(AuthSessionUnavailableException.class, () -> retry.execute(() -> { attempts.incrementAndGet(); throw new CannotGetJdbcConnectionException("test", sql); }));
            assertEquals(1, attempts.get());
        }
    }
    @Test void connectionInterruptionsAndAcquisitionTimeoutsAreClassifiedAsTemporary() {
        LogoutRetry retry = new LogoutRetry();
        assertTrue(retry.retryable(temporary(0, "08S01")));
        assertTrue(retry.retryable(new CannotGetJdbcConnectionException("test", new SQLTransientConnectionException())));
        assertTrue(retry.retryable(new CannotGetJdbcConnectionException("test", new SQLRecoverableException())));
    }
    @Test void permanentUnderlyingCauseOverridesTransientWrapper() {
        var sql = new SQLTransientConnectionException("test"); sql.initCause(new SQLException("test", "28000", 1045));
        assertFalse(new LogoutRetry().retryable(new CannotGetJdbcConnectionException("test", sql)));
    }
    @Test void insufficientRemainingBudgetStopsBeforeAnotherAttempt() {
        AtomicLong nanos = new AtomicLong(); AtomicInteger attempts = new AtomicInteger();
        LogoutRetry retry = new LogoutRetry(nanos::get, millis -> fail("budget already exhausted"));
        assertThrows(AuthSessionUnavailableException.class, () -> retry.execute(() -> { attempts.incrementAndGet(); nanos.addAndGet(5_500_000_000L); throw temporary(1205, "HY000"); }));
        assertEquals(1, attempts.get());
    }
    @Test void businessRejectionsAreNeverRetried() {
        var expected = new com.nexus.auth.exception.InvalidAccessTokenException();
        assertSame(expected, assertThrows(com.nexus.auth.exception.InvalidAccessTokenException.class,
                () -> new LogoutRetry(() -> 0L, millis -> fail()).execute(() -> { throw expected; })));
    }
    @Test void certificateFailuresAreNotRetriedEvenInsideAConnectionWrapper() {
        var sql = new SQLTransientConnectionException("test", "08001");
        sql.initCause(new java.security.cert.CertificateException("controlled invalid certificate"));
        assertFalse(new LogoutRetry().retryable(new CannotGetJdbcConnectionException("test", sql)));
    }
    @Test void interruptedBackoffPreservesTheInterruptAndStopsFurtherAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        try {
            var retry = new LogoutRetry(() -> 0L, millis -> { throw new InterruptedException("controlled interrupt"); });
            assertThrows(AuthSessionUnavailableException.class, () -> retry.execute(() -> { attempts.incrementAndGet(); throw temporary(1213, "40001"); }));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, attempts.get());
        } finally { Thread.interrupted(); }
    }
    private static DataAccessResourceFailureException temporary(int code, String state) {
        return new DataAccessResourceFailureException("test database failure", new SQLException("test", state, code));
    }
}
