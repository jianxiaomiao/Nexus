package com.nexus.usage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.exception.ApiKeyNotFoundException;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.usage.ApiCode;
import com.nexus.usage.dto.TimeRange;
import com.nexus.usage.dto.UsageQueryRequest;
import com.nexus.usage.dto.UsageQueryResponse;
import com.nexus.usage.entity.UsageEvent;
import com.nexus.usage.exception.InvalidUsageTimeRangeException;
import com.nexus.usage.mapper.UsageApiCountRow;
import com.nexus.usage.mapper.UsageDailyCountRow;
import com.nexus.usage.mapper.UsageEventMapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class UsageQueryService {
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration MAX_CUSTOM_RANGE = Duration.ofDays(90);

    private final UsageEventMapper usageEventMapper;
    private final ApplicationMapper applicationMapper;
    private final ApiKeyMapper apiKeyMapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UsageQueryResponse queryMyUsageEvent(Long userId, UsageQueryRequest usageQueryRequest) {
        //校验application
        Application application = applicationMapper.selectOne(
                Wrappers.<Application>lambdaQuery()
                        .eq(Application::getId, usageQueryRequest.applicationId())
                        .eq(Application::getOwnerUserId, userId)
                        .eq(Application::getIsDeleted, 0)
        );
        if (application == null) {
            throw new ApplicationNotFoundException();
        }
        //校验apiKey
        ApiKey apiKey = apiKeyMapper.selectOne(
                Wrappers.<ApiKey>lambdaQuery()
                        .eq(ApiKey::getId, usageQueryRequest.apiKeyId())
                        .eq(ApiKey::getApplicationId, application.getId())
                        .eq(ApiKey::getIsDeleted, 0)
        );
        if (apiKey == null) {
            throw new ApiKeyNotFoundException();
        }
        // 禁用只阻止机器调用，不影响所属用户查看历史用量。
        LocalDateTime startLocal;
        LocalDateTime endLocal;
        TimeRange timeRange = usageQueryRequest.timeRange();
        if (timeRange == null) {
            throw new InvalidUsageTimeRangeException("请选择时间范围");
        }
        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        if (timeRange == TimeRange.CUSTOM) {
            // 前端的无时区日期时间按北京时间解释。
            startLocal = usageQueryRequest.customStartTime();
            endLocal = usageQueryRequest.customEndTime();
            if (startLocal == null || endLocal == null) {
                throw new InvalidUsageTimeRangeException("自定义时间范围必须传入customStartTime、customEndTime");
            }
            if (!startLocal.isBefore(endLocal)) {
                throw new InvalidUsageTimeRangeException("自定义时间范围的开始时间必须早于结束时间");
            }
            if (Duration.between(startLocal, endLocal).compareTo(MAX_CUSTOM_RANGE) > 0) {
                throw new InvalidUsageTimeRangeException("自定义时间范围不能超过90天");
            }
        } else {
            TimeRange.TimeRangeBound bound = timeRange.getBound(today);
            startLocal = bound.start();
            endLocal = bound.end();
        }

        // occurred_at 存储 UTC；先将页面使用的北京时间边界转换为 UTC，再用 [start, end) 过滤。
        LocalDateTime startUtc = startLocal.atZone(DISPLAY_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime endUtc = endLocal.atZone(DISPLAY_ZONE)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        long allTimeCount = usageEventMapper.selectCount(
                Wrappers.<UsageEvent>lambdaQuery()
                        .eq(UsageEvent::getApiKeyId, apiKey.getId())
                        .eq(UsageEvent::getApplicationId, application.getId())
        );
        List<UsageDailyCountRow> dailyRows = usageEventMapper.selectDailyCounts(
                apiKey.getId(), application.getId(), startUtc, endUtc);
        List<UsageApiCountRow> apiRows = usageEventMapper.selectApiCounts(
                apiKey.getId(), application.getId(), startUtc, endUtc);

        Map<LocalDate, Long> countsByDate = dailyRows.stream().collect(Collectors.toMap(
                UsageDailyCountRow::getDate, UsageDailyCountRow::getCallCount));
        LocalDate lastIncludedDate = endLocal.minusNanos(1).toLocalDate();
        List<UsageQueryResponse.DailyCount> dailyCounts = startLocal.toLocalDate()
                .datesUntil(lastIncludedDate.plusDays(1))
                .map(date -> new UsageQueryResponse.DailyCount(date, countsByDate.getOrDefault(date, 0L)))
                .toList();
        long periodCount = dailyCounts.stream().mapToLong(UsageQueryResponse.DailyCount::count).sum();

        List<UsageQueryResponse.ApiCount> apiCounts = apiRows.stream()
                .map(row -> new UsageQueryResponse.ApiCount(
                        row.getApiCode(), apiName(row.getApiCode()), row.getCallCount()))
                .toList();

        return new UsageQueryResponse(
                allTimeCount,
                periodCount,
                DISPLAY_ZONE.getId(),
                startLocal.atZone(DISPLAY_ZONE).toOffsetDateTime(),
                endLocal.atZone(DISPLAY_ZONE).toOffsetDateTime(),
                dailyCounts,
                apiCounts
        );
    }

    private String apiName(String code) {
        for (ApiCode apiCode : ApiCode.values()) {
            if (apiCode.getCode().equals(code)) {
                return apiCode.getDesc();
            }
        }
        return code;
    }
}
