package com.nexus.openapi.ratelimit;

public final class InvalidRateLimitApplicationIdException extends RuntimeException {

    public InvalidRateLimitApplicationIdException() {
        super("限流身份中的 Application ID 必须为正数");
    }
}
