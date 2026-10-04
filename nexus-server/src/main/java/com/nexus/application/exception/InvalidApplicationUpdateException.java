package com.nexus.application.exception;

public class InvalidApplicationUpdateException extends RuntimeException {
    public InvalidApplicationUpdateException(String message) {
        super(message);
    }
}
