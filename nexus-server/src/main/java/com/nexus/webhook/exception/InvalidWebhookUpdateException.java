package com.nexus.webhook.exception;

public class InvalidWebhookUpdateException extends RuntimeException {
    public InvalidWebhookUpdateException(String message) { super(message); }
}
