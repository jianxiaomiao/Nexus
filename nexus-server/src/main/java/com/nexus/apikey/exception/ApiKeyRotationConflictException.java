package com.nexus.apikey.exception;

public class ApiKeyRotationConflictException extends RuntimeException {
    public ApiKeyRotationConflictException() {
        super("密钥凭据已变化，请刷新后重试");
    }
}
