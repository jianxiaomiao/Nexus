package com.nexus.webhook.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@TableName("webhook_outbox")
public class WebhookOutbox {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;
    private String eventType;
    private Long applicationId;
    private String jobId;
    private Integer eventStatus;
    private LocalDateTime completedAt;
    private Long resultSizeBytes;
    private String resultSha256;
    private LocalDateTime resultExpiresAt;
    private String failureCode;
    private String queryUrl;
    private String deliveryUrl;
    private Long webhookSecretId;
    private Integer status;
    /** Committed admissions (initial, retry, refresh), not guaranteed network starts or completions. */
    private Long attemptCount;
    /** A secret-refresh admission committed; it does not mean its network call completed. */
    private Boolean secretRefreshAttempted;
    private LocalDateTime nextAttemptAt;
    private String claimToken;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime deliveredAt;
    private String lastFailureCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
