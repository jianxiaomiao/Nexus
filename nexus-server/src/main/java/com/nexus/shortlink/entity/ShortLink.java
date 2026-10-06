package com.nexus.shortlink.entity;

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
@TableName("short_links")
public class ShortLink {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long apiKeyId;
    private String name;
    private String originalUrl;
    private String shortCode;
    private Integer status;
    private Integer isDeleted;
    private LocalDateTime expiresAt;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
