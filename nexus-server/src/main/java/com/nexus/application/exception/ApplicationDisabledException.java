package com.nexus.application.exception;

public class ApplicationDisabledException extends RuntimeException {
    public ApplicationDisabledException() {
        super("应用已禁用，无法创建密钥");
    }
}
