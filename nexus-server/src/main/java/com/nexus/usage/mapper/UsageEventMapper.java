package com.nexus.usage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.usage.entity.UsageEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface UsageEventMapper extends BaseMapper<UsageEvent> {
    @Select("""
            SELECT DATE(CONVERT_TZ(occurred_at, '+00:00', '+08:00')) AS usage_date,
                   COUNT(*) AS call_count
            FROM usage_events
            WHERE api_key_id = #{apiKeyId}
              AND application_id = #{applicationId}
              AND occurred_at >= #{startUtc}
              AND occurred_at < #{endUtc}
            GROUP BY usage_date
            ORDER BY usage_date
            """)
    @Results({
            @Result(property = "date", column = "usage_date"),
            @Result(property = "callCount", column = "call_count")
    })
    List<UsageDailyCountRow> selectDailyCounts(@Param("apiKeyId") long apiKeyId,
                                                @Param("applicationId") long applicationId,
                                                @Param("startUtc") LocalDateTime startUtc,
                                                @Param("endUtc") LocalDateTime endUtc);

    @Select("""
            SELECT api_code, COUNT(*) AS call_count
            FROM usage_events
            WHERE api_key_id = #{apiKeyId}
              AND application_id = #{applicationId}
              AND occurred_at >= #{startUtc}
              AND occurred_at < #{endUtc}
            GROUP BY api_code
            ORDER BY call_count DESC, api_code
            """)
    @Results({
            @Result(property = "apiCode", column = "api_code"),
            @Result(property = "callCount", column = "call_count")
    })
    List<UsageApiCountRow> selectApiCounts(@Param("apiKeyId") long apiKeyId,
                                            @Param("applicationId") long applicationId,
                                            @Param("startUtc") LocalDateTime startUtc,
                                            @Param("endUtc") LocalDateTime endUtc);
}
