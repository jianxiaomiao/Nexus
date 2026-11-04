package com.nexus.webhook.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.UnknownHostException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.sql.SQLException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

class WebhookExceptionClassifierTests {
    private final WebhookExceptionClassifier classifier = new WebhookExceptionClassifier();
    private WebhookFailure classify(Throwable t, WebhookExceptionClassifier.Operation operation, LeaseStatus status) {
        return classifier.classify(t, operation, status);
    }

    @Test void boundariesAndTypesNeverLeakMessages() {
        var op = WebhookExceptionClassifier.Operation.DELIVERY;
        assertEquals(FailureCategory.RETRYABLE, classify(new UnknownHostException("private"), op, LeaseStatus.ACTIVE).category());
        assertEquals(FailureCategory.PERMANENT, classify(new WebhookDisallowedAddressException(), op, LeaseStatus.ACTIVE).category());
        assertEquals(FailureCategory.PERMANENT, classify(new IllegalArgumentException("secret"), WebhookExceptionClassifier.Operation.SECRET_DECRYPTION, LeaseStatus.ACTIVE).category());
        assertEquals(FailureCategory.PERMANENT, classify(new IllegalArgumentException("url"), WebhookExceptionClassifier.Operation.URL_VALIDATION, LeaseStatus.ACTIVE).category());
        assertEquals("UNCLASSIFIED_ERROR", classify(new RuntimeException("sensitive token"), op, LeaseStatus.ACTIVE).failureCode());
        assertEquals(FailureCategory.INFRASTRUCTURE, classify(new RuntimeException(new SQLException("commit", new IOException())), op, LeaseStatus.ACTIVE).category());
        assertEquals(FailureCategory.INFRASTRUCTURE, classify(new IOException(new SQLException()), op, LeaseStatus.ACTIVE).category());
        assertEquals(FailureCategory.INFRASTRUCTURE, classify(new NullPointerException(), WebhookExceptionClassifier.Operation.URL_VALIDATION, LeaseStatus.ACTIVE).category());
        assertEquals("DNS_RESOLUTION_FAILED", classify(new CompletionException(new UnknownHostException()), WebhookExceptionClassifier.Operation.URL_VALIDATION, LeaseStatus.ACTIVE).failureCode());
        var wrappedSecurity = new UnknownHostException(); wrappedSecurity.initCause(new WebhookDisallowedAddressException());
        assertEquals("ADDRESS_DISALLOWED", classify(wrappedSecurity, WebhookExceptionClassifier.Operation.URL_VALIDATION, LeaseStatus.ACTIVE).failureCode());
        assertEquals(FailureCategory.LEASE, classify(new RuntimeException(new LeaseUncertainException(new IOException())), op, LeaseStatus.ACTIVE).category());
    }

    @Test void cancellationUsesSnapshotAndLeaseWinsOverDatabase() {
        var op = WebhookExceptionClassifier.Operation.DELIVERY;
        for (LeaseStatus status : LeaseStatus.values()) {
            var result = classify(new CancellationException(), op, status);
            var category = result.category();
            assertEquals(status == LeaseStatus.LEASE_LOST || status == LeaseStatus.LEASE_UNCERTAIN
                    ? FailureCategory.LEASE : FailureCategory.INFRASTRUCTURE, category);
            assertEquals(switch (status) {
                case ACTIVE -> "CANCELLED";
                case LEASE_LOST -> "LEASE_LOST";
                case LEASE_UNCERTAIN -> "LEASE_UNCERTAIN";
                case COMPLETED -> "LEASE_COMPLETED";
            }, result.failureCode());
        }
        assertEquals(FailureCategory.LEASE, classify(new SQLException(), op, LeaseStatus.LEASE_LOST).category());
        assertEquals(FailureCategory.INFRASTRUCTURE, classify(new SQLException(), WebhookExceptionClassifier.Operation.LEASE, LeaseStatus.ACTIVE).category());
        assertEquals("LEASE_UNCERTAIN", classify(new LeaseLostException(null), op, LeaseStatus.LEASE_UNCERTAIN).failureCode());
        assertEquals("LEASE_LOST", classify(new LeaseUncertainException(null), op, LeaseStatus.LEASE_LOST).failureCode());
    }

    @Test void wrappedInterruptRestoresFlagEvenIfDatabaseTakesPriority() {
        try {
            var result = classify(new SQLException("commit", new InterruptedException()), WebhookExceptionClassifier.Operation.DELIVERY, LeaseStatus.ACTIVE);
            assertEquals(FailureCategory.INFRASTRUCTURE, result.category());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void causeCyclesTerminateAndFatalErrorsEscape() {
        var a = new RuntimeException(); var b = new RuntimeException(a); a.initCause(b);
        assertEquals("UNCLASSIFIED_ERROR", classify(a, WebhookExceptionClassifier.Operation.OTHER, LeaseStatus.ACTIVE).failureCode());
        assertThrows(OutOfMemoryError.class, () -> classify(new RuntimeException(new OutOfMemoryError()), WebhookExceptionClassifier.Operation.OTHER, LeaseStatus.ACTIVE));
        assertThrows(ThreadDeath.class, () -> classify(new RuntimeException(new ThreadDeath()), WebhookExceptionClassifier.Operation.OTHER, LeaseStatus.ACTIVE));
    }

    @Test void contextHandlesExpectedWrappedFailuresOnly() {
        for (var op : new WebhookExceptionClassifier.Operation[]{WebhookExceptionClassifier.Operation.URL_VALIDATION,
                WebhookExceptionClassifier.Operation.SECRET_DECRYPTION, WebhookExceptionClassifier.Operation.CONFIGURATION}) {
            assertEquals(FailureCategory.PERMANENT, classify(new CompletionException(new ExecutionException(new IllegalArgumentException())), op, LeaseStatus.ACTIVE).category());
        }
        assertEquals(FailureCategory.PERMANENT, classify(new GeneralSecurityException(), WebhookExceptionClassifier.Operation.SECRET_DECRYPTION, LeaseStatus.ACTIVE).category());
        for (var op : WebhookExceptionClassifier.Operation.values()) {
            assertEquals(FailureCategory.INFRASTRUCTURE, classify(new RuntimeException("sensitive"), op, LeaseStatus.ACTIVE).category());
            assertEquals(FailureCategory.INFRASTRUCTURE, classify(new NullPointerException("sensitive"), op, LeaseStatus.ACTIVE).category());
            assertEquals(FailureCategory.INFRASTRUCTURE, classify(new AssertionError("sensitive"), op, LeaseStatus.ACTIVE).category());
        }
        assertEquals("UNCLASSIFIED_ERROR", classify(new IllegalArgumentException("secret"), WebhookExceptionClassifier.Operation.OTHER, LeaseStatus.ACTIVE).failureCode());
    }

    @Test void actualNetworkTlsAndDatabaseExceptionsAreSeparated() {
        var op = WebhookExceptionClassifier.Operation.DELIVERY;
        for (Throwable t : new Throwable[]{new ConnectException(), new SocketTimeoutException(), new UnknownHostException()})
            assertEquals(FailureCategory.RETRYABLE, classify(t, op, LeaseStatus.ACTIVE).category());
        for (Throwable t : new Throwable[]{new SSLHandshakeException("x"), new SSLPeerUnverifiedException("x"), new CertificateException(), new CertPathBuilderException(), new CertPathValidatorException()})
            assertEquals(FailureCategory.PERMANENT, classify(new RuntimeException(t), op, LeaseStatus.ACTIVE).category());
        for (Throwable t : new Throwable[]{new DataAccessResourceFailureException("x", new ConnectException()), new TransactionSystemException("commit", new SocketTimeoutException())})
            assertEquals("DATABASE_UNCERTAIN", classify(t, op, LeaseStatus.ACTIVE).failureCode());
        assertEquals("LEASE_LOST", classify(new CompletionException(new LeaseLostException(new ConnectException())), op, LeaseStatus.ACTIVE).failureCode());
    }

    @Test void ordinaryAndLeaseInterruptRestoreFlag() {
        try {
            assertEquals("INTERRUPTED", classify(new CompletionException(new InterruptedException()), WebhookExceptionClassifier.Operation.DELIVERY, LeaseStatus.ACTIVE).failureCode());
            assertTrue(Thread.interrupted());
            assertEquals("LEASE_UNCERTAIN", classify(new InterruptedException(), WebhookExceptionClassifier.Operation.DELIVERY, LeaseStatus.LEASE_UNCERTAIN).failureCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
