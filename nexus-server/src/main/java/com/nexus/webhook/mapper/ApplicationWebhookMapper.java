package com.nexus.webhook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.webhook.entity.ApplicationWebhook;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ApplicationWebhookMapper extends BaseMapper<ApplicationWebhook> {
    @Select("SELECT * FROM application_webhooks WHERE application_id = #{applicationId}")
    ApplicationWebhook selectByApplicationId(@Param("applicationId") long applicationId);

    @Select("SELECT * FROM application_webhooks WHERE application_id = #{applicationId} FOR UPDATE")
    ApplicationWebhook selectByApplicationIdForUpdate(@Param("applicationId") long applicationId);
}
