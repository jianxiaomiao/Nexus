package com.nexus.webhook.dispatch;

import com.nexus.common.net.PublicWebDnsResolver;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.Proxy;
import java.net.Socket;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.impl.NoConnectionReuseStrategy;
import org.apache.hc.core5.util.Timeout;

/** A direct, one-request-per-connection transport; it never follows redirects. */
public final class WebhookHttpClient implements AutoCloseable {
    private final WebhookUrlPolicy policy = new WebhookUrlPolicy();
    private final PublicWebDnsResolver.AddressLookup lookup;
    private final SSLContext sslContext;
    private final boolean allowLoopbackForTest;
    private final Set<CloseableHttpClient> active = new HashSet<>();
    private boolean closed;

    public WebhookHttpClient() {
        this(InetAddress::getAllByName, null, false);
    }

    // Package-private test seam: the production constructor always enforces public addresses and system trust.
    WebhookHttpClient(PublicWebDnsResolver.AddressLookup lookup, SSLContext sslContext, boolean allowLoopbackForTest) {
        Objects.requireNonNull(lookup);
        if (!allowLoopbackForTest && Boolean.getBoolean("java.net.preferIPv4Stack"))
            throw new IllegalStateException("Webhook DNS requires IPv4 and IPv6 support");
        this.lookup = lookup;
        this.sslContext = sslContext;
        this.allowLoopbackForTest = allowLoopbackForTest;
    }

    private CloseableHttpClient createClient(DnsResolver resolver) {
        var manager = new PoolingHttpClientConnectionManagerBuilder() {
            @Override protected org.apache.hc.client5.http.io.HttpClientConnectionOperator createConnectionOperator(
                    org.apache.hc.client5.http.SchemePortResolver ports, DnsResolver dns,
                    org.apache.hc.client5.http.ssl.TlsSocketStrategy tls) {
                return new DefaultHttpClientConnectionOperator(proxy -> new Socket(Proxy.NO_PROXY), ports, dns,
                        scheme -> "https".equalsIgnoreCase(scheme) ? tls : null);
            }
        }.setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3)).build());
        if (sslContext != null) manager.setTlsSocketStrategy(ClientTlsStrategyBuilder.create().setSslContext(sslContext).buildClassic());
        return HttpClients.custom().setConnectionManager(manager.build())
                .setRoutePlanner(new DefaultRoutePlanner(null))
                .setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE)
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(2))
                        .setResponseTimeout(Timeout.ofSeconds(6)).setHardCancellationEnabled(true).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableAuthCaching().build();
    }

    /** Resolve and validate every address before admission; the returned request is single use. */
    public PreparedPost preparePost(String target, byte[] body) throws UnknownHostException {
        return preparePost(target, body, null, null);
    }

    public PreparedPost preparePost(String target, byte[] body, Long timestamp, String signature) throws UnknownHostException {
        if ((timestamp == null) != (signature == null)) throw new IllegalArgumentException("incomplete signature");
        synchronized (this) { if (closed) throw new IllegalStateException("client closed"); }
        URI uri = policy.requireAllowedUrl(target);
        byte[] payload = Objects.requireNonNull(body).clone();
        InetAddress[] addresses;
        if (!allowLoopbackForTest) addresses = WebhookUrlPolicy.checked(uri.getHost(), lookup);
        else {
            addresses = lookup.lookup(uri.getHost());
            if (addresses == null || addresses.length == 0) throw new UnknownHostException("No addresses");
            for (InetAddress address : addresses)
                if (address == null || !address.isLoopbackAddress()) throw new UnknownHostException("Expected loopback");
        }
        return new PreparedPost(uri, payload, addresses.clone(), timestamp, signature);
    }

    public final class PreparedPost {
        private final URI uri;
        private final byte[] body;
        private final InetAddress[] addresses;
        private final AtomicBoolean used = new AtomicBoolean();
        private final HttpPost request;
        private PreparedPost(URI uri, byte[] body, InetAddress[] addresses, Long timestamp, String signature) {
            this.uri = uri; this.body = body; this.addresses = addresses;
            this.request = new HttpPost(uri);
            if (timestamp != null) {
                request.setHeader("X-Nexus-Timestamp", Long.toString(timestamp));
                request.setHeader("X-Nexus-Signature", signature);
            }
        }
        public void cancel() { request.cancel(); }
        /** Uses only prevalidated addresses while retaining the original hostname for TLS and Host. */
        public WebhookHttpResponse send() throws IOException {
            if (!used.compareAndSet(false, true)) throw new IllegalStateException("prepared request already used");
            DnsResolver pinned = new DnsResolver() {
                @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                    if (!uri.getHost().equalsIgnoreCase(host)) throw new UnknownHostException("Unexpected host");
                    return addresses.clone();
                }
                @Override public String resolveCanonicalHostname(String host) { return host; }
            };
            CloseableHttpClient client;
            synchronized (WebhookHttpClient.this) {
                if (closed) throw new IllegalStateException("client closed");
                client = createClient(pinned);
                active.add(client);
            }
            try {
                request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
                var response = org.apache.hc.client5.http.impl.classic.CloseableHttpResponse.adapt(client.executeOpen(null, request, null));
                try {
                    var header = response.getFirstHeader("Retry-After");
                    return new WebhookHttpResponse(response.getCode(), header == null ? null : header.getValue());
                } finally { response.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE); }
            } finally {
                synchronized (WebhookHttpClient.this) { active.remove(client); }
                client.close();
            }
        }
    }

    /** Convenience only: delivery code prepares before committing its admission. */
    public WebhookHttpResponse post(String target, byte[] body) throws IOException {
        return preparePost(target, body).send();
    }

    @Override public synchronized void close() throws IOException {
        closed = true;
        IOException failure = null;
        for (CloseableHttpClient client : active) {
            try { client.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        active.clear();
        if (failure != null) throw failure;
    }
}
