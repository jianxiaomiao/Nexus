package com.nexus.shortlink.exception;

public class InvalidShortLinkRequestException extends RuntimeException {
    public InvalidShortLinkRequestException(String message) {
        super(message);
    }
}
