package com.nexus.webhook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.webhook.entity.WebhookSecret;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Select("""
            SELECT *
            FROM webhook_secrets
            WHERE webhook_id = #{webhookId}
              AND status = 0
              AND (expires_at IS NULL OR expires_at > #{now})
            FOR UPDATE
            """)
    List<WebhookSecret> selectCurrentSecretsByWebhookIdForUpdate(
            @Param("webhookId") Long webhookId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE webhook_secrets
            SET status = 1, expires_at = #{expiresAt}
            WHERE id = #{id} AND webhook_id = #{webhookId}
              AND secret_version = #{secretVersion} AND status = 0
            """)
    int moveCurrentToGrace(@Param("id") Long id,
                           @Param("webhookId") Long webhookId,
                           @Param("secretVersion") Long secretVersion,
                           @Param("expiresAt") LocalDateTime expiresAt);
}
