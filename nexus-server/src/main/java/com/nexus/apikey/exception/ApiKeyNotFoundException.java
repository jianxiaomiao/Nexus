package com.nexus.apikey.exception;

public class ApiKeyNotFoundException extends RuntimeException {
    public ApiKeyNotFoundException() {
        super("未找到对应密钥");
    }
}
