package com.nexus.admin.exception;

public class InvalidAdminOperationException extends RuntimeException {
    public InvalidAdminOperationException(String message) {
        super(message);
    }
}
