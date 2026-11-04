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
@TableName("application_webhooks")
public class ApplicationWebhook {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long applicationId;
    private String url;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
