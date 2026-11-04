package com.nexus.webhook.dispatch;

import com.nexus.webhook.mapper.WebhookOutboxMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;

@Component
public class WebhookLeaseRecovery {
    private final WebhookOutboxMapper mapper;
    private final TransactionTemplate transaction;
    private final int batchSize;

    public WebhookLeaseRecovery(WebhookOutboxMapper mapper, PlatformTransactionManager manager,
            @Value("${nexus.webhook.scheduling.recovery-batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("recovery batch size must be 1..1000");
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.batchSize = batchSize;
    }

    public void recoverExpired() {
        transaction.executeWithoutResult(status -> {
            for (var event : mapper.selectExpiredForUpdate(batchSize)) {
                mapper.recoverExpired(event.getId(), event.getClaimToken());
            }
        });
    }
}
