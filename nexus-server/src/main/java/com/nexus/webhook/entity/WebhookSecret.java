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
@TableName("webhook_secrets")
public class WebhookSecret {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long webhookId;
    private byte[] secretCiphertext;
    private byte[] secretNonce;
    private String encryptionAlgorithm;
    private String encryptionKeyVersion;
    private Long secretVersion;
    private Integer status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
