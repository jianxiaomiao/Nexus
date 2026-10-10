package com.nexus.webExtract.service;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** 在 HTTP 客户端真正建立连接时校验 DNS 结果，避免“先校验、后重新解析”的间隙。 */
final class PublicWebDnsResolver implements DnsResolver {
    private final WebExtractUrlPolicy policy;
    private final AddressLookup lookup;

    PublicWebDnsResolver(WebExtractUrlPolicy policy) {
        this(policy, InetAddress::getAllByName);
    }

    PublicWebDnsResolver(WebExtractUrlPolicy policy, AddressLookup lookup) {
        this.policy = policy;
        this.lookup = lookup;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        if (!policy.isAllowedHost(host)) {
            throw new UnknownHostException("目标主机不在允许列表内");
        }
        InetAddress[] addresses = lookup.lookup(host);
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !policy.isPublicAddress(address))) {
            throw new UnknownHostException("目标主机解析到了不允许的地址");
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        if (!policy.isAllowedHost(host)) {
            throw new UnknownHostException("目标主机不在允许列表内");
        }
        return host;
    }

    @FunctionalInterface
    interface AddressLookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }
}
