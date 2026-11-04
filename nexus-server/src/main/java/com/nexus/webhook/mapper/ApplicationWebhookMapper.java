package com.nexus.webhook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.webhook.entity.ApplicationWebhook;

public interface ApplicationWebhookMapper extends BaseMapper<ApplicationWebhook> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM application_webhooks WHERE application_id = #{applicationId}")
    ApplicationWebhook selectByApplicationId(@org.apache.ibatis.annotations.Param("applicationId") long applicationId);
}
