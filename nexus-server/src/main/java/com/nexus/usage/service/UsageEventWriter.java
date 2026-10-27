package com.nexus.usage.service;

import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.usage.entity.UsageEvent;
import com.nexus.usage.mapper.UsageEventMapper;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UsageEventWriter {
    private final UsageEventMapper usageEventMapper;
    private final ApplicationMapper applicationMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final TransactionTemplate usageTransaction;

    public UsageEventWriter(UsageEventMapper usageEventMapper,
                            ApplicationMapper applicationMapper,
                            ApiKeyMapper apiKeyMapper,
                            PlatformTransactionManager transactionManager) {
        this.usageEventMapper = usageEventMapper;
        this.applicationMapper = applicationMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.usageTransaction = new TransactionTemplate(transactionManager);
    }

    @Retryable(includes = TransientDataAccessException.class, maxRetries = 2,
            delay = 500, multiplier = 2)
    public void insertWithRetry(UsageEvent usageEvent) {
        usageTransaction.executeWithoutResult(status -> {
            // 所有父级写路径统一为 Application → API Key；共享锁足以保护外键父记录，
            // 并避免 Usage INSERT 的隐式 Key → Application 外键检查与 Quota 形成反向锁序。
            applicationMapper.selectIdForShare(usageEvent.getApplicationId());
            apiKeyMapper.selectIdForShare(
                    usageEvent.getApiKeyId(), usageEvent.getApplicationId());
            usageEventMapper.insert(usageEvent);
        });
    }
}
