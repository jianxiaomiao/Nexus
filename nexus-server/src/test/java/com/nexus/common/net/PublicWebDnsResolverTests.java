package com.nexus.common.net;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PublicWebDnsResolverTests {
    @Test
    void rejectsHostBeforeLookup() {
        AtomicInteger calls = new AtomicInteger();
        var resolver = new PublicWebDnsResolver("blog.csdn.net"::equals, host -> { calls.incrementAndGet(); return new InetAddress[0]; });
        assertThrows(UnknownHostException.class, () -> resolver.resolve("blog.csdn.net.evil.example"));
        assertEquals(0, calls.get());
    }
    @Test
    void returnsTheSameValidatedResultsWithoutASecondLookup() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var addresses = new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("2001:4860:4860::8888")};
        var resolver = new PublicWebDnsResolver(host -> true, host -> { calls.incrementAndGet(); return addresses; });
        assertSame(addresses, resolver.resolve("blog.csdn.net"));
        assertEquals(1, calls.get());
    }
    @Test
    void deniesEmptyAndMixedPublicPrivateResults() throws Exception {
        assertThrows(UnknownHostException.class, () -> new PublicWebDnsResolver(host -> true, host -> new InetAddress[0]).resolve("blog.csdn.net"));
        var mixed = new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")};
        assertThrows(UnknownHostException.class, () -> new PublicWebDnsResolver(host -> true, host -> mixed).resolve("blog.csdn.net"));
    }
}
