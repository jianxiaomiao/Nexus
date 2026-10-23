package com.nexus.job.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.exception.ScreenshotJobCreationException;
import com.nexus.job.exception.ScreenshotJobFailedException;
import com.nexus.job.exception.ScreenshotJobNotFoundException;
import com.nexus.job.exception.ScreenshotJobNotReadyException;
import com.nexus.job.exception.ScreenshotJobQueueFullException;
import com.nexus.job.exception.ScreenshotJobResultExpiredException;
import com.nexus.job.exception.ScreenshotJobSubmissionUnavailableException;
import com.nexus.job.mapper.ScreenshotJobMapper;
import com.nexus.job.model.ScreenshotJobStatus;
import com.nexus.job.store.ScreenshotResultStore;
import com.nexus.quota.service.ApplicationQuotaService;
import com.nexus.screenshot.service.ScreenshotUrlPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ScreenshotJobService {
    private static final Logger log = LoggerFactory.getLogger(ScreenshotJobService.class);
    private static final int MAX_UNFINISHED_JOBS = 10;

    private final ScreenshotJobMapper jobMapper;
    private final ScreenshotUrlPolicy urlPolicy;
    private final ApplicationQuotaService quotaService;
    private final ScreenshotResultStore resultStore;
    private final Clock clock;
    // 第一版只部署一个 Nexus JVM；该锁只串行化本 JVM 的全局任务准入。
    private final ReentrantLock admissionLock = new ReentrantLock(true);

    public ScreenshotJobService(ScreenshotJobMapper jobMapper,
                                ScreenshotUrlPolicy urlPolicy,
                                ApplicationQuotaService quotaService,
                                ScreenshotResultStore resultStore,
                                Clock clock) {
        this.jobMapper = jobMapper;
        this.urlPolicy = urlPolicy;
        this.quotaService = quotaService;
        this.resultStore = resultStore;
        this.clock = clock;
    }

    public ScreenshotJob submit(ApiKeyIdentity identity, String rawUrl) {
        URI uri = urlPolicy.requirePage(rawUrl);
        requireValidIdentity(identity);

        admissionLock.lock();
        try {
            if (jobMapper.countUnfinished() >= MAX_UNFINISHED_JOBS) {
                throw new ScreenshotJobQueueFullException();
            }
            try {
                return quotaService.consumeOnceAndCommit(
                        identity, () -> insertQueuedJob(identity.apiKeyId(), uri));
            } catch (DataAccessException | TransactionException exception) {
                // 不记录 SQL、参数或目标 URL，只保留异常类型作为服务端诊断。
                log.error("截图任务提交数据库操作失败 exception={}",
                        exception.getClass().getSimpleName());
                throw new ScreenshotJobSubmissionUnavailableException();
            }
        } finally {
            admissionLock.unlock();
        }
    }

    public ScreenshotJob getJob(ApiKeyIdentity identity, String jobId) {
        ScreenshotJob job = requireOwnedJob(identity, jobId);
        if (ScreenshotJobStatus.fromCode(job.getStatus()) == ScreenshotJobStatus.SUCCEEDED
                && resultExpired(job)) {
            // 清理器最多晚一分钟落库；HTTP 查询仍以绝对到期时间为准。
            job.setStatus(ScreenshotJobStatus.EXPIRED.code());
        }
        return job;
    }

    public byte[] getResult(ApiKeyIdentity identity, String jobId) {
        ScreenshotJob job = requireOwnedJob(identity, jobId);
        return switch (ScreenshotJobStatus.fromCode(job.getStatus())) {
            case QUEUED -> throw new ScreenshotJobNotReadyException(ScreenshotJobStatus.QUEUED);
            case RUNNING -> throw new ScreenshotJobNotReadyException(ScreenshotJobStatus.RUNNING);
            case SUCCEEDED -> readUnexpiredResult(job);
            case FAILED -> throw new ScreenshotJobFailedException(job.getFailureCode());
            case EXPIRED -> throw new ScreenshotJobResultExpiredException();
        };
    }

    private ScreenshotJob requireOwnedJob(ApiKeyIdentity identity, String jobId) {
        requireValidIdentity(identity);
        if (jobId == null || jobId.isBlank() || jobId.length() > 36) {
            throw new ScreenshotJobNotFoundException();
        }
        ScreenshotJob job = jobMapper.selectOne(Wrappers.<ScreenshotJob>lambdaQuery()
                .eq(ScreenshotJob::getJobId, jobId)
                .eq(ScreenshotJob::getApiKeyId, identity.apiKeyId()));
        if (job == null) {
            throw new ScreenshotJobNotFoundException();
        }
        return job;
    }

    private byte[] readUnexpiredResult(ScreenshotJob job) {
        LocalDateTime resultExpiresAt = job.getResultExpiresAt();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (resultExpiresAt == null || !now.isBefore(resultExpiresAt)) {
            throw new ScreenshotJobResultExpiredException();
        }
        return resultStore.read(job.getJobId());
    }

    private boolean resultExpired(ScreenshotJob job) {
        LocalDateTime expiresAt = job.getResultExpiresAt();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return expiresAt == null || !now.isBefore(expiresAt);
    }

    private ScreenshotJob insertQueuedJob(long apiKeyId, URI uri) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        ScreenshotJob job = new ScreenshotJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setApiKeyId(apiKeyId);
        job.setTargetUrl(uri.toString());
        job.setStatus(ScreenshotJobStatus.QUEUED.code());
        job.setAttemptCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        if (jobMapper.insert(job) != 1) {
            throw new ScreenshotJobCreationException();
        }
        return job;
    }

    private static void requireValidIdentity(ApiKeyIdentity identity) {
        if (identity == null || identity.apiKeyId() == null || identity.apiKeyId() <= 0
                || identity.applicationId() == null || identity.applicationId() <= 0) {
            throw new InvalidApiKeyCredentialException();
        }
    }
}
