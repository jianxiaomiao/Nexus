package com.nexus.quota;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.quota.entity.ApplicationQuota;
import com.nexus.quota.mapper.ApplicationQuotaMapper;

public final class QuotaTestCleanup {
    private QuotaTestCleanup() {}

    public static void deletePeriods(ApplicationQuotaMapper mapper, long applicationId) {
        mapper.delete(Wrappers.<ApplicationQuota>lambdaQuery()
                .eq(ApplicationQuota::getApplicationId, applicationId));
    }
}
