package com.nexus.shortlink.exception;

public class ShortLinkExpiredException extends RuntimeException {
    public ShortLinkExpiredException() {
        super("短链接已到期");
    }
}
