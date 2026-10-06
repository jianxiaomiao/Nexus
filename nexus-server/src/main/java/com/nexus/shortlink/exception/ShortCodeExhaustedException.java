package com.nexus.shortlink.exception;

public class ShortCodeExhaustedException extends RuntimeException {
    public ShortCodeExhaustedException() {
        super("暂时无法分配短码，请稍后重试");
    }
}
