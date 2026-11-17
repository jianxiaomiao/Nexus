package com.nexus.auth.token;

/** 原文只交给客户端；持久化时只取 tokenHash，不能将本对象作为数据库实体。 */
public record GeneratedRefreshToken(
        String value,
        String tokenHash
) {
    @Override
    public String toString() {
        return "GeneratedRefreshToken[<redacted>]";
    }
}
