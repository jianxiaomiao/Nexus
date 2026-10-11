package com.nexus.common.net;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.function.Predicate;

/** 在 HTTP 客户端真正建立连接时校验 DNS 结果，避免“先校验、后重新解析”的间隙。 */
public final class PublicWebDnsResolver implements DnsResolver {
    private final Predicate<String> allowedHost;
    private final AddressLookup lookup;

    public PublicWebDnsResolver(Predicate<String> allowedHost) {
        this(allowedHost, InetAddress::getAllByName);
    }

    public PublicWebDnsResolver(Predicate<String> allowedHost, AddressLookup lookup) {
        this.allowedHost = allowedHost;
        this.lookup = lookup;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        if (!allowedHost.test(host)) {
            throw new UnknownHostException("目标主机不在允许列表内");
        }
        InetAddress[] addresses = lookup.lookup(host);
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !PublicIpAddresses.isPublic(address))) {
            throw new UnknownHostException("目标主机解析到了不允许的地址");
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        if (!allowedHost.test(host)) {
            throw new UnknownHostException("目标主机不在允许列表内");
        }
        return host;
    }

    @FunctionalInterface
    public interface AddressLookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }
}
