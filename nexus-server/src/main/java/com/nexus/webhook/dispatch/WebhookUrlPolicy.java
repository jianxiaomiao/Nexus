package com.nexus.webhook.dispatch;

import com.nexus.common.net.PublicIpAddresses;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/** URL syntax and connection-time address checks for arbitrary webhook domains. */
public final class WebhookUrlPolicy {
    public URI requireAllowedUrl(String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid webhook URL"); }
        String host = uri.getHost();
        String authority = uri.getRawAuthority();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || authority == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() == 0
                || uri.getPort() > 65535 || authority.endsWith(":") || host.endsWith(".")
                || !host.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?") || host.contains(".."))
            throw new IllegalArgumentException("Invalid webhook URL");
        String last = host.substring(host.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        // Reject literals and legacy numeric forms before Apache can bypass its resolver.
        if (last.matches("[0-9]+") || last.startsWith("0x") || host.indexOf(':') >= 0)
            throw new IllegalArgumentException("Webhook host must be a domain name");
        for (String label : host.split("\\.")) {
            if (label.length() > 63 || label.startsWith("-") || label.endsWith("-"))
                throw new IllegalArgumentException("Invalid webhook host");
        }
        if (host.length() > 253) throw new IllegalArgumentException("Invalid webhook host");
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (!PublicIpAddresses.isPublic(address)) return false;
        byte[] b = address.getAddress();
        if (b.length != 16) return true;
        int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
        return !(a == 0x20 && c == 0x01 && d <= 0x01) // 2001::/23
                && !(a == 0x20 && c == 0x02) // 6to4
                && !(a == 0x3f && c == 0xff && (d & 0xf0) == 0); // 3fff::/20
    }

    static InetAddress[] checked(String host, com.nexus.common.net.PublicWebDnsResolver.AddressLookup lookup)
            throws UnknownHostException {
        InetAddress[] addresses = lookup.lookup(host);
        if (addresses == null || addresses.length == 0) throw new UnknownHostException("No webhook addresses");
        addresses = addresses.clone();
        for (InetAddress address : addresses)
            if (address == null || !isPublic(address)) throw new WebhookDisallowedAddressException();
        return addresses;
    }
}
