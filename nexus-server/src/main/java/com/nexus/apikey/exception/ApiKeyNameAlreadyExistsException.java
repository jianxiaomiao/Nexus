package com.nexus.apikey.exception;

public class ApiKeyNameAlreadyExistsException extends RuntimeException {
    public ApiKeyNameAlreadyExistsException(Throwable cause) {
        super("密钥名称已存在", cause);
    }
}
