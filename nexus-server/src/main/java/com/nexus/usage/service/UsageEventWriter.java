package com.nexus.usage.service;

import com.nexus.usage.entity.UsageEvent;
import com.nexus.usage.mapper.UsageEventMapper;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
public class UsageEventWriter {
    private final UsageEventMapper usageEventMapper;

    public UsageEventWriter(UsageEventMapper usageEventMapper) {
        this.usageEventMapper = usageEventMapper;
    }

    @Retryable(includes = TransientDataAccessException.class, maxRetries = 2,
            delay = 500, multiplier = 2)
    public void insertWithRetry(UsageEvent usageEvent) {
        usageEventMapper.insert(usageEvent);
    }
}
