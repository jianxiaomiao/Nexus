package com.nexus.webhook.dispatch;

import com.nexus.webhook.entity.WebhookOutbox;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebhookLeaseRecoveryTests {
    @Test void updateFailureRollsBackWholeBatch() {
        var mapper = mock(WebhookOutboxMapper.class);
        var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var event = new WebhookOutbox(); event.setId(5L); event.setClaimToken("token");
        when(mapper.selectExpiredForUpdate(10)).thenReturn(List.of(event));
        when(mapper.recoverExpired(5L, "token")).thenThrow(new IllegalStateException());
        var recovery = new WebhookLeaseRecovery(mapper, manager, 10);
        assertThrows(IllegalStateException.class, recovery::recoverExpired);
        verify(manager).rollback(status);
        verify(manager, never()).commit(status);
    }
}
