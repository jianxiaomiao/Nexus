package com.nexus.webhook.dispatch;

public final class LeaseLostException extends RuntimeException {
    public LeaseLostException(Throwable cause) { super("Webhook lease lost", cause); }
}
