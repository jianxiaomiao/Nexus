package com.nexus.webhook.service;

import com.nexus.webhook.dispatch.WebhookDisallowedAddressException;
import com.nexus.webhook.dispatch.WebhookUrlPolicy;
import com.nexus.webhook.exception.InvalidWebhookUrlException;
import com.nexus.webhook.exception.WebhookAddressDisallowedException;
import com.nexus.webhook.exception.WebhookDnsResolutionException;
import com.nexus.common.net.PublicWebDnsResolver;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

@Component
public class WebhookEndpointValidator {
    private final WebhookUrlPolicy policy = new WebhookUrlPolicy();
    private final PublicWebDnsResolver.AddressLookup lookup;

    public WebhookEndpointValidator() {
        this(InetAddress::getAllByName);
    }

    WebhookEndpointValidator(PublicWebDnsResolver.AddressLookup lookup) {
        this.lookup = lookup;
    }

    public String validate(String value) {
        if (value == null || value.isBlank() || value.length() > 2048) {
            throw new InvalidWebhookUrlException();
        }
        URI uri;
        try {
            uri = policy.requireAllowedUrl(value.strip());
        } catch (IllegalArgumentException exception) {
            throw new InvalidWebhookUrlException();
        }
        try {
            WebhookUrlPolicy.checked(uri.getHost(), lookup);
        } catch (WebhookDisallowedAddressException exception) {
            throw new WebhookAddressDisallowedException(exception);
        } catch (UnknownHostException exception) {
            throw new WebhookDnsResolutionException(exception);
        }
        return uri.toASCIIString();
    }
}
