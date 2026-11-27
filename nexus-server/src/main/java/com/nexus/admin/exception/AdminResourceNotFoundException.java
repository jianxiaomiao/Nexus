package com.nexus.admin.exception;

public class AdminResourceNotFoundException extends RuntimeException {
    public AdminResourceNotFoundException(String message) {
        super(message);
    }
}
