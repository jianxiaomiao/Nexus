package com.nexus.auth.exception;

public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException(Throwable cause) {
        super("该邮箱已被注册", cause);
    }
}