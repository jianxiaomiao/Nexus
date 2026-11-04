package com.nexus.webhook.dispatch;


/** Distinguishes a security rejection from a transient DNS lookup failure. */
public final class WebhookDisallowedAddressException extends SecurityException {
    public WebhookDisallowedAddressException() { super("Disallowed webhook address"); }
}
