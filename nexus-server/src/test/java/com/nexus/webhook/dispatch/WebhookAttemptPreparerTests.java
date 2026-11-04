package com.nexus.webhook.dispatch;

import com.nexus.webhook.mapper.WebhookOutboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.nexus.webhook.dispatch.WebhookAttemptPreparer.*;

class WebhookAttemptPreparerTests {
    @Test void failedOrUncertainAdmissionNeverCallsHttp() {
        var mapper = mock(WebhookOutboxMapper.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        var gate = new WebhookAttemptPreparer(mapper, new TransactionTemplate(manager), 2);
        var send = mock(Runnable.class);
        when(mapper.lockForCompletion(1)).thenReturn(1L);
        when(mapper.prepareAttempt(anyLong(), anyString(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(0, 1);
        gate.prepare(1, "token", new Snapshot(0, false), AttemptType.INITIAL).ifPresent(p -> send.run());
        verifyNoInteractions(send);
        doThrow(new TransactionSystemException("commit uncertain")).when(manager).commit(any());
        assertThrows(TransactionSystemException.class, () -> gate.prepare(1, "token", new Snapshot(0, false), AttemptType.INITIAL).ifPresent(p -> send.run()));
        verifyNoInteractions(send);
    }

    @Test void callbackRunsOnlyOnceAndAfterCommit() throws Exception {
        var mapper = mock(WebhookOutboxMapper.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        when(mapper.lockForCompletion(1)).thenReturn(1L);
        when(mapper.prepareAttempt(anyLong(), anyString(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(0, 1, 1);
        var gate = new WebhookAttemptPreparer(mapper, new TransactionTemplate(manager), 2);
        var send = mock(Runnable.class);
        assertTrue(gate.prepareAndSend(1, "token", new Snapshot(0, false), AttemptType.INITIAL,
                snapshot -> { send.run(); return 200; }).isEmpty());
        verifyNoInteractions(send);
        var commitFailure = new TransactionSystemException("uncertain");
        doThrow(commitFailure).when(manager).commit(any());
        assertSame(commitFailure, assertThrows(TransactionSystemException.class, () -> gate.prepareAndSend(1, "token", new Snapshot(0, false),
                AttemptType.INITIAL, snapshot -> { send.run(); return 200; })));
        verifyNoInteractions(send);
        doNothing().when(manager).commit(any());
        var result = gate.prepareAndSend(1, "token", new Snapshot(0, false), AttemptType.INITIAL, snapshot -> {
            verify(manager, times(3)).commit(any());
            send.run(); return 200;
        }).orElseThrow();
        assertEquals(new Snapshot(1, false), result.snapshot());
        assertEquals(200, result.response());
        verify(send, times(1)).run();
    }

    @Test void changedTransactionPropagationFailsBeforeAnyDatabaseOrHttpCall() {
        var mapper = mock(WebhookOutboxMapper.class);
        var manager = mock(PlatformTransactionManager.class);
        var transaction = new TransactionTemplate(manager);
        var gate = new WebhookAttemptPreparer(mapper, transaction, 1);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_SUPPORTS);
        var send = mock(Runnable.class);
        assertThrows(IllegalStateException.class, () -> gate.prepareAndSend(1, "token", new Snapshot(0, false),
                AttemptType.INITIAL, snapshot -> { send.run(); return 200; }));
        verifyNoInteractions(mapper, manager, send);
    }

    @Test void typesAndBoundariesAreCheckedBeforeDatabase() {
        var mapper = mock(WebhookOutboxMapper.class);
        var manager = mock(PlatformTransactionManager.class);
        var gate = new WebhookAttemptPreparer(mapper, new TransactionTemplate(manager), 1);
        assertTrue(gate.prepare(1, "t", new Snapshot(1, false), AttemptType.INITIAL).isEmpty());
        assertTrue(gate.prepare(1, "t", new Snapshot(0, false), AttemptType.SECRET_REFRESH).isEmpty());
        assertTrue(gate.prepare(1, "t", new Snapshot(2, true), AttemptType.SECRET_REFRESH).isEmpty());
        assertTrue(gate.prepare(1, "t", new Snapshot(3, true), AttemptType.ORDINARY_RETRY).isEmpty());
        assertEquals(1, new Snapshot(3, true).ordinaryRetriesUsed());
        assertThrows(IllegalArgumentException.class, () -> new Snapshot(1, true));
        verifyNoInteractions(mapper, manager);
    }
    @Test void sendFailuresCarryCommittedSnapshotAndPreserveClassification() {
        var mapper = mock(WebhookOutboxMapper.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        when(mapper.lockForCompletion(1)).thenReturn(1L);
        when(mapper.prepareAttempt(anyLong(), anyString(), anyLong(), anyBoolean(), anyBoolean())).thenReturn(1);
        var gate = new WebhookAttemptPreparer(mapper, new TransactionTemplate(manager), 2);
        var classifier = new WebhookExceptionClassifier();
        for (var cause : new Exception[]{new java.io.IOException("private"), new IllegalStateException("private"), new java.util.concurrent.CancellationException("private")}) {
            var failure = assertThrows(WebhookSendException.class, () -> gate.prepareAndSend(1, "t", new Snapshot(0, false), AttemptType.INITIAL, snapshot -> {
                if (cause instanceof java.io.IOException io) throw io;
                throw (RuntimeException) cause;
            }));
            assertEquals(new Snapshot(1, false), failure.snapshot());
            assertSame(cause, failure.getCause());
            assertEquals("Webhook send failed after admission", failure.getMessage());
            assertEquals(cause instanceof java.io.IOException ? FailureCategory.RETRYABLE : FailureCategory.INFRASTRUCTURE,
                    classifier.classify(failure, WebhookExceptionClassifier.Operation.DELIVERY, LeaseStatus.ACTIVE).category());
            if (cause.getClass() == IllegalStateException.class)
                assertEquals("UNCLASSIFIED_ERROR", classifier.classify(failure, WebhookExceptionClassifier.Operation.DELIVERY, LeaseStatus.ACTIVE).failureCode());
            if (cause instanceof java.util.concurrent.CancellationException)
                assertEquals(FailureCategory.LEASE, classifier.classify(failure, WebhookExceptionClassifier.Operation.DELIVERY, LeaseStatus.LEASE_LOST).category());
        }
        var fatal = new AssertionError("fatal");
        assertSame(fatal, assertThrows(AssertionError.class, () -> gate.prepareAndSend(1, "t", new Snapshot(0, false), AttemptType.INITIAL, snapshot -> { throw fatal; })));
    }

}
