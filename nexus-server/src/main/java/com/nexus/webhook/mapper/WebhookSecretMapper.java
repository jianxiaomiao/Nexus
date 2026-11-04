package com.nexus.webhook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.webhook.entity.WebhookSecret;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface WebhookSecretMapper extends BaseMapper<WebhookSecret> {
    @Select("""
            SELECT *
            FROM webhook_secrets
            WHERE webhook_id = #{webhookId}
              AND status = 0
              AND (expires_at IS NULL OR expires_at > #{now})
            """)
    List<WebhookSecret> selectCurrentSecretsByWebhookId(
            @Param("webhookId") Long webhookId,
            @Param("now") LocalDateTime now);
}
