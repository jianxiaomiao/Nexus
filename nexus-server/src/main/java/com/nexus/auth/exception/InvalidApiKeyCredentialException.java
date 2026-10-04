package com.nexus.auth.exception;

public class InvalidApiKeyCredentialException extends RuntimeException {
    public InvalidApiKeyCredentialException() {
        super("API Key 无效");
    }
}
