package com.nexus.auth.exception;

public class AdminForbiddenException extends RuntimeException {
    public AdminForbiddenException() {
        super("需要管理员权限");
    }
}
