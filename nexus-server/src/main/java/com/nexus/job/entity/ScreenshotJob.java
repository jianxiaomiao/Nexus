package com.nexus.job.entity;

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
@TableName("screenshot_jobs")
public class ScreenshotJob {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String jobId;
    private Long apiKeyId;
    private String targetUrl;
    private Integer status;
    private Integer attemptCount;
    private String runToken;
    private LocalDateTime leaseExpiresAt;
    private Long resultSizeBytes;
    private String resultSha256;
    private Integer blockedResources;
    private Boolean resourceLimitReached;
    private LocalDateTime resultExpiresAt;
    private String failureCode;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
