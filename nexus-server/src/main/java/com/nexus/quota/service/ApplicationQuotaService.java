package com.nexus.quota.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.exception.InvalidApplicationIdException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.auth.exception.ApiKeyForbiddenException;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import com.nexus.quota.dto.QueryQuotaRequest;
import com.nexus.quota.dto.QueryQuotaResponse;
import com.nexus.quota.entity.ApplicationQuota;
import com.nexus.quota.exception.ApplicationQuotaExhaustedException;
import com.nexus.quota.mapper.ApplicationQuotaMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ApplicationQuotaService {
    private static final Duration PERIOD = Duration.ofHours(720);
    // applications.created_at 由本地 MySQL CURRENT_TIMESTAMP 写入，按北京时间解释。
    // DATETIME 不携带时区；周期记录沿用同一约定，不把已有创建时间直接当作 UTC。
    private static final ZoneId APPLICATION_TIME_ZONE = ZoneId.of("Asia/Shanghai");

    private final ApplicationMapper applicationMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ApplicationQuotaMapper quotaMapper;
    private final Clock clock;
    private final TransactionTemplate quotaTransaction;

    public ApplicationQuotaService(ApplicationMapper applicationMapper, ApiKeyMapper apiKeyMapper,
                                   ApplicationQuotaMapper quotaMapper, Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.applicationMapper = applicationMapper;
        this.apiKeyMapper = apiKeyMapper;
        this.quotaMapper = quotaMapper;
        this.clock = clock;
        this.quotaTransaction = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public QueryQuotaResponse queryMyApplicationQuota(Long userId, QueryQuotaRequest request) {
        if (request == null || request.applicationId() == null || request.applicationId() <= 0) {
            throw new InvalidApplicationIdException();
        }
        if (userId == null) {
            throw new ApplicationNotFoundException();
        }
        Application application = applicationMapper.selectOne(Wrappers.<Application>lambdaQuery()
                .eq(Application::getId, request.applicationId())
                .eq(Application::getOwnerUserId, userId)
                .eq(Application::getIsDeleted, 0));
        if (application == null) {
            throw new ApplicationNotFoundException();
        }
        // 禁用阻止机器调用，但所属用户仍可查看配额。查询不创建周期记录。
        LocalDateTime start = currentPeriodStart(application.getCreatedAt(), clock.instant());
        ApplicationQuota quota = quotaMapper.selectPeriod(application.getId(), start);
        return quotaResponse(application, start, quota);
    }

    /** 仅供服务端已按用户归属筛选的分页结果使用，返回顺序与输入一致；不创建记录。 */
    @Transactional(readOnly = true)
    public List<QueryQuotaResponse> queryCurrentQuotas(List<Application> applications) {
        if (applications.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        List<LocalDateTime> starts = applications.stream()
                .map(app -> currentPeriodStart(app.getCreatedAt(), now)).toList();
        LambdaQueryWrapper<ApplicationQuota> query = Wrappers.lambdaQuery();
        for (int index = 0; index < applications.size(); index++) {
            Long applicationId = applications.get(index).getId();
            LocalDateTime start = starts.get(index);
            query.or(pair -> pair.eq(ApplicationQuota::getApplicationId, applicationId)
                    .eq(ApplicationQuota::getPeriodStart, start));
        }
        // 一条 SQL 精确匹配本页的 (Application ID, 当前周期)，不读取历史或页外应用。
        Map<Long, ApplicationQuota> byApplication = quotaMapper.selectList(query).stream()
                .collect(Collectors.toMap(ApplicationQuota::getApplicationId, quota -> quota));
        List<QueryQuotaResponse> responses = new ArrayList<>(applications.size());
        for (int index = 0; index < applications.size(); index++) {
            Application app = applications.get(index);
            responses.add(quotaResponse(app, starts.get(index), byApplication.get(app.getId())));
        }
        return responses;
    }

    private QueryQuotaResponse quotaResponse(Application application, LocalDateTime start, ApplicationQuota quota) {
        int limit = quota == null ? application.getQuota() : quota.getQuotaLimit();
        int used = quota == null ? 0 : quota.getUsedCount();
        return new QueryQuotaResponse(start, start.plusHours(720), limit, used, limit - used);
    }

    /** 校验完成后、业务事务和实际执行开始前调用；返回时额度事务已提交。 */
    public void consumeOnce(ApiKeyIdentity identity) {
        if (identity == null || identity.applicationId() == null || identity.applicationId() <= 0
                || identity.apiKeyId() == null || identity.apiKeyId() <= 0) {
            throw new InvalidApiKeyCredentialException();
        }
        // 禁止意外加入长业务事务，也不在持有 Application 锁的外层事务中另开连接争锁。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Quota must be consumed before the business transaction");
        }
        quotaTransaction.executeWithoutResult(status -> {
            // 与现有父级删除保持 Application -> Key 加锁顺序；锁仅持有到这次准入提交。
            Application application = applicationMapper.selectOne(Wrappers.<Application>lambdaQuery()
                    .eq(Application::getId, identity.applicationId()).last("FOR UPDATE"));
            if (application == null || Integer.valueOf(1).equals(application.getIsDeleted())) {
                throw new InvalidApiKeyCredentialException();
            }
            ApiKey key = apiKeyMapper.selectOne(Wrappers.<ApiKey>lambdaQuery()
                    .eq(ApiKey::getId, identity.apiKeyId())
                    .eq(ApiKey::getApplicationId, application.getId()).last("FOR UPDATE"));
            if (key == null || Integer.valueOf(1).equals(key.getIsDeleted())) {
                throw new InvalidApiKeyCredentialException();
            }
            if (Integer.valueOf(1).equals(application.getStatus()) || Integer.valueOf(1).equals(key.getStatus())) {
                throw new ApiKeyForbiddenException();
            }
            // 在取得锁后读取时间；跨过周期边界的等待请求使用取得准入时所属的周期。
            LocalDateTime start = currentPeriodStart(application.getCreatedAt(), clock.instant());
            ensureCurrentPeriod(application, start);
            int changed = quotaMapper.consumeOne(application.getId(), start);
            if (changed == 0) {
                throw new ApplicationQuotaExhaustedException();
            }
            if (changed != 1) {
                throw new IllegalStateException("Quota update must affect exactly one period");
            }
        });
    }

    /** 调用者已在同一事务中持有 Application 行锁，同应用首次创建也会串行执行。 */
    private void ensureCurrentPeriod(Application application, LocalDateTime start) {
        if (quotaMapper.selectPeriod(application.getId(), start) != null) {
            return;
        }
        ApplicationQuota quota = new ApplicationQuota();
        quota.setApplicationId(application.getId());
        quota.setPeriodStart(start);
        quota.setQuotaLimit(application.getQuota());
        quota.setUsedCount(0);
        if (quotaMapper.insert(quota) != 1) {
            throw new IllegalStateException("Quota period was not created");
        }
        // 所有创建都经过上面的 Application 锁；意外的约束/数据库错误原样传播。
    }

    static LocalDateTime currentPeriodStart(LocalDateTime createdAt, Instant now) {
        if (createdAt == null) {
            throw new IllegalStateException("Application creation time is missing");
        }
        Instant anchor = createdAt.atZone(APPLICATION_TIME_ZONE).toInstant();
        if (now.isBefore(anchor)) {
            throw new IllegalStateException("Current time precedes application creation");
        }
        long periods = Duration.between(anchor, now).dividedBy(PERIOD);
        return LocalDateTime.ofInstant(anchor.plus(PERIOD.multipliedBy(periods)), APPLICATION_TIME_ZONE);
    }
}
