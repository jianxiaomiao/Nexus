package com.nexus.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 用户和会话身份由匹配的 RT 数据库记录推导，不接受客户端另报身份。 */
public record RefreshRequest(
        @NotBlank(message = "刷新凭据不能为空") String refreshToken
) {
    @Override
    public String toString() {
        return "RefreshRequest[refreshToken=<redacted>]";
    }
}
