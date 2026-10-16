package com.nexus.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.quota.entity.ApplicationQuota;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface ApplicationQuotaMapper extends BaseMapper<ApplicationQuota> {
    @Select("""
            SELECT id, application_id, period_start, quota_limit, used_count
            FROM application_quotas
            WHERE application_id = #{applicationId} AND period_start = #{periodStart}
            """)
    ApplicationQuota selectPeriod(@Param("applicationId") long applicationId,
                                  @Param("periodStart") LocalDateTime periodStart);

    @Update("""
            UPDATE application_quotas
            SET used_count = used_count + 1
            WHERE application_id = #{applicationId}
              AND period_start = #{periodStart}
              AND used_count < quota_limit
            """)
    int consumeOne(@Param("applicationId") long applicationId,
                   @Param("periodStart") LocalDateTime periodStart);
}
