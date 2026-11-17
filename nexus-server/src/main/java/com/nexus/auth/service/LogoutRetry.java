package com.nexus.auth.service;

import com.nexus.auth.exception.AuthSessionUnavailableException;
import org.springframework.dao.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import javax.net.ssl.SSLHandshakeException;
import java.sql.*;
import java.util.function.LongSupplier;

@Component
public class LogoutRetry {
    private static final long[] DELAYS_MS = {200, 400, 800};
    private static final long BUDGET_NS = 6_000_000_000L;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    public LogoutRetry() { this(System::nanoTime, Thread::sleep); }
    LogoutRetry(LongSupplier nanoTime, Sleeper sleeper) { this.nanoTime = nanoTime; this.sleeper = sleeper; }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }

    public void execute(Runnable attempt) {
        AuthSessionTransactions.requireNoOuterTransaction();
        long started = nanoTime.getAsLong();
        RuntimeException last = null;
        for (int index = 0; index <= DELAYS_MS.length; index++) {
            if (index > 0) {
                long delay = DELAYS_MS[index - 1];
                if (nanoTime.getAsLong() - started + delay * 1_000_000L + 1_000_000_000L >= BUDGET_NS) break;
                try { sleeper.sleep(delay); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AuthSessionUnavailableException(exception);
                }
            }
            if (nanoTime.getAsLong() - started >= BUDGET_NS) break;
            try { attempt.run(); return; }
            catch (DataAccessException | TransactionException exception) {
                last = exception;
                if (!retryable(exception)) break;
            }
        }
        throw new AuthSessionUnavailableException(last);
    }

    boolean retryable(Throwable failure) {
        boolean temporary = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLHandshakeException || cause instanceof java.security.cert.CertificateException) return false;
            if (cause instanceof SQLException sql) {
                for (SQLException error = sql; error != null; error = error.getNextException()) {
                    String state = error.getSQLState();
                    int code = error.getErrorCode();
                    if ((state != null && (state.startsWith("28") || state.startsWith("42") || state.startsWith("23")))
                            || code == 1044 || code == 1045 || code == 1049) return false;
                    if (code == 1213 || code == 1205 || "40001".equals(state)
                            || (state != null && state.startsWith("08"))
                            || error instanceof SQLTransientConnectionException
                            || error instanceof SQLRecoverableException || error instanceof SQLTimeoutException) temporary = true;
                }
            }
            if (cause instanceof CannotAcquireLockException || cause instanceof QueryTimeoutException
                    || cause instanceof TransactionTimedOutException) temporary = true;
        }
        return temporary;
    }
}
