package com.nexus.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** 创建登录会话时采用的绝对寿命；刷新不能据此重新延长已有会话。 */
@ConfigurationProperties(prefix = "nexus.auth.session")
public record AuthSessionProperties(
        @DefaultValue("24h") Duration absoluteTtl
) {
    public AuthSessionProperties {
        if (absoluteTtl == null || absoluteTtl.isZero() || absoluteTtl.isNegative()) {
            throw new IllegalArgumentException("会话绝对寿命必须为正数时长");
        }
    }
}
