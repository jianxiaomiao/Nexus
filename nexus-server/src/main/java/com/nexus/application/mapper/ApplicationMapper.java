package com.nexus.application.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.application.entity.Application;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ApplicationMapper extends BaseMapper<Application> {
    @Select("""
            SELECT id
            FROM applications
            WHERE id = #{applicationId}
            FOR SHARE
            """)
    Long selectIdForShare(@Param("applicationId") long applicationId);
}
