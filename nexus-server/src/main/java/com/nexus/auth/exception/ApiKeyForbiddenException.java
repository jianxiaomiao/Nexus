package com.nexus.auth.exception;

public class ApiKeyForbiddenException extends RuntimeException {
    public ApiKeyForbiddenException() {
        super("API Key、所属应用或账号已禁用");
    }
}
