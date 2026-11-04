package com.nexus.webhook.dispatch;

public final class LeaseUncertainException extends RuntimeException {
    public LeaseUncertainException(Throwable cause) { super("Webhook lease uncertain", cause); }
}
