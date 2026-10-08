package com.nexus.usage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@TableName("usage_events")
public class UsageEvent {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long apiKeyId;
    private Long applicationId;
    private String apiCode;
    private Integer httpStatusCode;
    private Integer durationMs;
    private LocalDateTime occurredAt;
    private String eventId;
}
