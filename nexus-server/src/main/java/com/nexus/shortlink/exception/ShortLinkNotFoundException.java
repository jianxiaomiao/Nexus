package com.nexus.shortlink.exception;

public class ShortLinkNotFoundException extends RuntimeException {
    public ShortLinkNotFoundException() {
        super("短链接不存在或不可用");
    }
}
