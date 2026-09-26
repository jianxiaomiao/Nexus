package com.nexus.auth.exception;

public class AccountForbiddenException extends RuntimeException{
    public AccountForbiddenException() {
        super("账号已封禁");
    }
}
