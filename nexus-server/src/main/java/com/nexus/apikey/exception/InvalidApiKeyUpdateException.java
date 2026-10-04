package com.nexus.apikey.exception;

public class InvalidApiKeyUpdateException extends RuntimeException {
    public InvalidApiKeyUpdateException(String message) {
        super(message);
    }
}
