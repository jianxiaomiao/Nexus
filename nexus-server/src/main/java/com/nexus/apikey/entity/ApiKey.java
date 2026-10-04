package com.nexus.apikey.entity;

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
@TableName("api_keys")
public class ApiKey {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long applicationId;
    private String name;
    private String publicId;
    private String secretHash;
    private String keyPreview;
    private Integer status;
    private Integer isDeleted;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
