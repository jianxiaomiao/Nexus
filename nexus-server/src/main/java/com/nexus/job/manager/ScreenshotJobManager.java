package com.nexus.job.manager;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.mapper.ScreenshotJobMapper;
import com.nexus.job.store.ScreenshotResultStore;
import com.nexus.job.store.ScreenshotResultStoreException;
import com.nexus.job.store.StoredScreenshot;
import com.nexus.screenshot.exception.ScreenshotException;
import com.nexus.screenshot.service.ScreenshotExecutionGate;
import com.nexus.screenshot.service.ScreenshotRenderer;
import com.nexus.screenshot.service.ScreenshotResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Component
public class ScreenshotJobManager {
    private static final Logger log = LoggerFactory.getLogger(ScreenshotJobManager.class);
    private static final int STATUS_RUNNING = 1;
    private static final int MAX_ATTEMPTS = 2;
    private static final int MAINTENANCE_BATCH_SIZE = 100;
    private static final int LEASE_SECONDS = 30;
    private static final int RESULT_RETENTION_HOURS = 1;
    private static final int METADATA_RETENTION_HOURS = 24;

    private final ScreenshotJobMapper jobMapper;
    private final ScreenshotRenderer renderer;
    private final ScreenshotResultStore resultStore;
    private final ScreenshotExecutionGate executionGate;
    private final Clock clock;
    private final TransactionTemplate claimTransaction;

    public ScreenshotJobManager(ScreenshotJobMapper jobMapper,
                                ScreenshotRenderer renderer,
                                ScreenshotResultStore resultStore,
                                ScreenshotExecutionGate executionGate,
                                Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.jobMapper = jobMapper;
        this.renderer = renderer;
        this.resultStore = resultStore;
        this.executionGate = executionGate;
        this.clock = clock;
        this.claimTransaction = new TransactionTemplate(transactionManager);
    }

    /** 没有排队任务时立即返回；有任务时公平等待共享 Chromium，但不在内存中增加第二层任务队列。 */
    public boolean pollAndRunOne() {
        if (jobMapper.selectCount(Wrappers.<ScreenshotJob>lambdaQuery()
                .eq(ScreenshotJob::getStatus, 0)) == 0) {
            return false;
        }
        try {
            executionGate.acquireForAsynchronousJob();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            ScreenshotJob job = claimNextJob();
            if (job == null) {
                return false;
            }
            executeClaimedJob(job);
            return true;
        } finally {
            executionGate.release();
        }
    }

    ScreenshotJob claimNextJob() {
        return claimTransaction.execute(status -> {
            ScreenshotJob job = jobMapper.selectNextQueuedForUpdate();
            if (job == null) {
                return null;
            }
            LocalDateTime startedAt = utcNow();
            String runToken = UUID.randomUUID().toString();
            int changed = jobMapper.markRunningIfQueued(
                    job.getId(), runToken, startedAt.plusSeconds(LEASE_SECONDS), startedAt);
            if (changed != 1) {
                throw new IllegalStateException("Screenshot job claim must affect exactly one row");
            }
            job.setStatus(STATUS_RUNNING);
            job.setAttemptCount(job.getAttemptCount() + 1);
            job.setRunToken(runToken);
            job.setLeaseExpiresAt(startedAt.plusSeconds(LEASE_SECONDS));
            if (job.getStartedAt() == null) {
                job.setStartedAt(startedAt);
            }
            return job;
        });
    }

    void executeClaimedJob(ScreenshotJob job) {
        try {
            ScreenshotResult rendered = renderer.render(URI.create(job.getTargetUrl()));
            StoredScreenshot stored = resultStore.saveAtomically(job.getJobId(), rendered.png());
            LocalDateTime completedAt = utcNow();
            int changed = jobMapper.completeSucceededIfRunTokenMatches(
                    job.getId(), job.getRunToken(), stored.sizeBytes(), stored.sha256(),
                    rendered.blockedResources(), rendered.resourceLimitReached(),
                    completedAt.plusHours(RESULT_RETENTION_HOURS), completedAt);
            if (changed == 0) {
                deleteResultQuietly(job.getJobId(), "stale-success");
            } else if (changed != 1) {
                throw new IllegalStateException("Screenshot job completion affected multiple rows");
            }
        } catch (ScreenshotException exception) {
            markKnownFailure(job, exception.code());
        } catch (ScreenshotResultStoreException exception) {
            markKnownFailure(job, "RESULT_STORAGE_FAILED");
        } catch (RuntimeException exception) {
            // 未确认数据库提交结果或未知内部故障时保留 RUNNING，由租约恢复规则接管。
            log.error("异步截图执行出现未分类故障 jobId={}, exception={}",
                    job.getJobId(), exception.getClass().getSimpleName(), exception);
        }
    }

    public void recoverExpiredLeases() {
        LocalDateTime now = utcNow();
        int failed = jobMapper.failExhaustedExpiredLeases(now, MAX_ATTEMPTS);
        int requeued = jobMapper.requeueExpiredLeases(now, MAX_ATTEMPTS);
        if (failed > 0 || requeued > 0) {
            log.info("异步截图租约恢复 requeued={}, failed={}", requeued, failed);
        }
    }

    public void expireResults() {
        LocalDateTime now = utcNow();
        List<ScreenshotJob> candidates =
                jobMapper.selectResultExpiryCandidates(now, MAINTENANCE_BATCH_SIZE);
        for (ScreenshotJob job : candidates) {
            if (jobMapper.markResultExpiredIfSucceeded(job.getId(), now) == 1) {
                deleteResultQuietly(job.getJobId(), "result-expiry");
            }
        }
    }

    public void cleanupMetadata() {
        LocalDateTime completedBefore = utcNow().minusHours(METADATA_RETENTION_HOURS);
        List<ScreenshotJob> candidates = jobMapper.selectMetadataCleanupCandidates(
                completedBefore, MAINTENANCE_BATCH_SIZE);
        for (ScreenshotJob job : candidates) {
            try {
                resultStore.deleteIfExists(job.getJobId());
                jobMapper.deleteTerminalIfEligible(job.getId(), completedBefore);
            } catch (ScreenshotResultStoreException exception) {
                log.error("异步截图清理结果文件失败 jobId={}", job.getJobId(), exception);
            }
        }
    }

    private void markKnownFailure(ScreenshotJob job, String failureCode) {
        LocalDateTime completedAt = utcNow();
        int changed = jobMapper.markFailedIfRunTokenMatches(
                job.getId(), job.getRunToken(), failureCode, completedAt);
        if (changed == 0) {
            log.info("忽略已失去执行权的截图失败结果 jobId={}", job.getJobId());
        } else if (changed != 1) {
            throw new IllegalStateException("Screenshot job failure affected multiple rows");
        }
    }

    private void deleteResultQuietly(String jobId, String phase) {
        try {
            resultStore.deleteIfExists(jobId);
        } catch (ScreenshotResultStoreException exception) {
            log.error("异步截图删除结果文件失败 jobId={}, phase={}", jobId, phase, exception);
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
