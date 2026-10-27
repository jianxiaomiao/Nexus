package com.nexus.apikey.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.apikey.entity.ApiKey;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ApiKeyMapper extends BaseMapper<ApiKey> {
    @Select("""
            SELECT id
            FROM api_keys
            WHERE id = #{apiKeyId}
              AND application_id = #{applicationId}
            FOR SHARE
            """)
    Long selectIdForShare(@Param("apiKeyId") long apiKeyId,
                          @Param("applicationId") long applicationId);
}
