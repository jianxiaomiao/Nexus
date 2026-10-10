package com.nexus.webExtract.service;

import com.nexus.webExtract.exception.InvalidWebExtractRequestException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class WebExtractUrlPolicyTests {
    private final WebExtractUrlPolicy policy = new WebExtractUrlPolicy();

    @Test
    void acceptsOnlyExactArticleHostsOverHttps() {
        assertEquals("zhuanlan.zhihu.com", policy.requireAllowedUrl("https://zhuanlan.zhihu.com/p/123").getHost());
        assertEquals("blog.csdn.net", policy.requireAllowedUrl("https://blog.csdn.net/user/article/details/1").getHost());
        for (String url : new String[]{
                "http://blog.csdn.net/a", "https://blog.csdn.net.evil.example/a",
                "https://user@blog.csdn.net/a", "https://blog.csdn.net:444/a",
                "https://127.0.0.1/a", "https://blog.csdn.net/a#fragment"
        }) {
            assertThrows(InvalidWebExtractRequestException.class, () -> policy.requireAllowedUrl(url), url);
        }
    }

    @Test
    void deniesPrivateAndSpecialUseDnsResultsAtConnectionBoundary() throws Exception {
        for (String address : new String[]{
                "127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1",
                "192.168.1.1", "198.18.0.1", "::1", "fc00::1", "2001:db8::1"
        }) {
            assertFalse(policy.isPublicAddress(InetAddress.getByName(address)), address);
        }
        assertTrue(policy.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(policy.isPublicAddress(InetAddress.getByName("2001:4860:4860::8888")));
    }

    @Test
    void dnsResolverRejectsUnlistedHostBeforeLookup() {
        PublicWebDnsResolver resolver = new PublicWebDnsResolver(policy);
        assertThrows(UnknownHostException.class, () -> resolver.resolve("localhost"));
    }

    @Test
    void dnsResolverRejectsPrivateResultForAllowedHost() throws Exception {
        PublicWebDnsResolver resolver = new PublicWebDnsResolver(policy,
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        assertThrows(UnknownHostException.class, () -> resolver.resolve("blog.csdn.net"));
    }
}
