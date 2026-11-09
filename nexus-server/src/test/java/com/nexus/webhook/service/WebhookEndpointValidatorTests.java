package com.nexus.webhook.service;

import com.nexus.webhook.exception.InvalidWebhookUrlException;
import com.nexus.webhook.exception.WebhookAddressDisallowedException;
import com.nexus.webhook.exception.WebhookDnsResolutionException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class WebhookEndpointValidatorTests {
    @Test
    void acceptsHttpsDomainWhenEveryResolvedAddressIsPublic() throws Exception {
        var validator = new WebhookEndpointValidator(host -> new InetAddress[]{
                InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})});

        assertEquals("https://hooks.example.com/nexus", validator.validate(" https://hooks.example.com/nexus "));
    }

    @Test
    void rejectsCredentialsFragmentsLiteralsAndPrivateAddresses() throws Exception {
        var publicDns = new WebhookEndpointValidator(host -> new InetAddress[]{
                InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})});
        assertThrows(InvalidWebhookUrlException.class,
                () -> publicDns.validate("https://user:pass@hooks.example.com/nexus"));
        assertThrows(InvalidWebhookUrlException.class,
                () -> publicDns.validate("https://hooks.example.com/nexus#fragment"));
        assertThrows(InvalidWebhookUrlException.class,
                () -> publicDns.validate("https://127.0.0.1/nexus"));

        var privateDns = new WebhookEndpointValidator(host -> new InetAddress[]{
                InetAddress.getByAddress(host, new byte[]{10, 0, 0, 1})});
        assertThrows(WebhookAddressDisallowedException.class,
                () -> privateDns.validate("https://hooks.example.com/nexus"));
    }

    @Test
    void distinguishesDnsFailureFromPolicyRejection() {
        var validator = new WebhookEndpointValidator(host -> {
            throw new UnknownHostException("test failure");
        });

        assertThrows(WebhookDnsResolutionException.class,
                () -> validator.validate("https://missing.example.com/nexus"));
    }
}
