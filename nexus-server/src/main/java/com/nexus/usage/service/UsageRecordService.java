package com.nexus.usage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.usage.ApiCode;
import com.nexus.usage.entity.UsageEvent;
import com.nexus.usage.mapper.UsageEventMapper;
import com.nexus.usage.metrics.UsageRecordMetrics;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;


@AllArgsConstructor
@Service
public class UsageRecordService {
    private static final Logger log = LoggerFactory.getLogger(UsageRecordService.class);

    private final UsageEventWriter usageEventWriter;
    private final UsageEventMapper usageEventMapper;
    private final UsageRecordMetrics metrics;


    @Async("usageExecutor")
    public void record(ApiCode apiCode, ApiKeyIdentity identity, int httpStatus, long costNs,
                       LocalDateTime startUtc, String eventId) {
        UsageEvent usageEvent = new UsageEvent();
        usageEvent.setApiCode(apiCode.getCode());
        usageEvent.setApiKeyId(identity.apiKeyId());
        usageEvent.setApplicationId(identity.applicationId());
        usageEvent.setHttpStatusCode(httpStatus);
        int durationMs = (int) (costNs / 1_000_000);
        usageEvent.setDurationMs(durationMs);
        usageEvent.setOccurredAt(startUtc);
        usageEvent.setEventId(eventId);

        try {
            usageEventWriter.insertWithRetry(usageEvent);
        } catch (DuplicateKeyException exception) {
            try {
                UsageEvent recorded = usageEventMapper.selectOne(
                        Wrappers.<UsageEvent>lambdaQuery()
                                .eq(UsageEvent::getEventId, eventId)
                );
                if (recorded != null) {
                    return;
                }
            } catch (Exception lookupFailure) {
                // 同一次事件只记一条终态错误日志，同时保留去重查询的失败堆栈。
                exception.addSuppressed(lookupFailure);
            }
            metrics.recordFailure(
                    UsageRecordMetrics.FailureReason.DUPLICATE_UNCONFIRMED);
            log.error("用量记录入库失败（重复键且未确认事件已写入）. apiCode={}, apiKeyId={}, appId={}, httpStatus={}, durationMs={}, occurredAt={}, eventId={}",
                    usageEvent.getApiCode(), usageEvent.getApiKeyId(), usageEvent.getApplicationId(),
                    usageEvent.getHttpStatusCode(), usageEvent.getDurationMs(), usageEvent.getOccurredAt(), usageEvent.getEventId(), exception);
        } catch (TransientDataAccessException exception) {
            metrics.recordFailure(
                    UsageRecordMetrics.FailureReason.TRANSIENT_WRITE_EXHAUSTED);
            log.error("用量记录入库失败（瞬时数据库异常重试耗尽）. apiCode={}, apiKeyId={}, appId={}, httpStatus={}, durationMs={}, occurredAt={}, eventId={}",
                    usageEvent.getApiCode(), usageEvent.getApiKeyId(), usageEvent.getApplicationId(),
                    usageEvent.getHttpStatusCode(), usageEvent.getDurationMs(), usageEvent.getOccurredAt(), usageEvent.getEventId(), exception);
        } catch (Exception exception) {
            metrics.recordFailure(UsageRecordMetrics.FailureReason.WRITE_FAILED);
            // 这里只记录内部 ID，不记录完整 API Key 或请求内容。
            log.error("用量记录入库失败. apiCode={}, apiKeyId={}, appId={}, httpStatus={}, durationMs={}, occurredAt={}, eventId={}",
                    usageEvent.getApiCode(), usageEvent.getApiKeyId(), usageEvent.getApplicationId(),
                    usageEvent.getHttpStatusCode(), usageEvent.getDurationMs(), usageEvent.getOccurredAt(), usageEvent.getEventId(), exception);
        }
    }
}
