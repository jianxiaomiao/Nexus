package com.nexus.shortlink.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nexus.shortlink.entity.ShortLink;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

public interface ShortLinkMapper extends BaseMapper<ShortLink> {
    @Select("""
            SELECT s.original_url, s.expires_at
            FROM short_links s
            JOIN api_keys k ON k.id = s.api_key_id
            JOIN applications a ON a.id = k.application_id
            JOIN users u ON u.id = a.owner_user_id
            WHERE s.short_code = #{shortCode}
              AND s.status = 0 AND s.is_deleted = 0
              AND k.status = 0 AND k.is_deleted = 0
              AND a.status = 0 AND a.is_deleted = 0
              AND u.status = 0 AND u.is_deleted = 0
            """)
    @ConstructorArgs({
            @Arg(column = "original_url", javaType = String.class),
            @Arg(column = "expires_at", javaType = LocalDateTime.class)
    })
    RedirectTarget findActiveRedirectTarget(@Param("shortCode") String shortCode);

    record RedirectTarget(String originalUrl, LocalDateTime expiresAt) {}
}
