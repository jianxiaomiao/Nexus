package com.nexus.apikey.exception;

public class InvalidApiKeyRotationException extends RuntimeException {
    public InvalidApiKeyRotationException() {
        super("密钥轮换请求无效");
    }
}
