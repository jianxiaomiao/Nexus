package com.nexus.auth.exception;

public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("刷新凭据无效或已失效");
    }
}
