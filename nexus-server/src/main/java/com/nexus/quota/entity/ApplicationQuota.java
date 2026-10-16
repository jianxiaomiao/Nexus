package com.nexus.quota.entity;

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
@TableName("application_quotas")
public class ApplicationQuota {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long applicationId;
    private LocalDateTime periodStart;
    private Integer quotaLimit;
    private Integer usedCount;
}
