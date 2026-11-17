package com.nexus.auth.websession;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@EnableConfigurationProperties(WebSessionProperties.class)
public class WebSessionPolicy {
    public static final String HEADER = "X-Nexus-Session-Transport";
    private final WebSessionProperties properties;
    private final Set<String> origins;

    public WebSessionPolicy(WebSessionProperties properties, Environment environment) {
        this.properties = properties;
        this.origins = properties.allowedOrigins().stream().map(WebSessionPolicy::checkedOrigin).collect(Collectors.toUnmodifiableSet());
        // Cookies ignore ports, while Web Locks are origin-scoped. Two ports on one host
        // would share a rotating RT without sharing the credential lock.
        if (origins.stream().map(o -> URI.create(o).getHost()).distinct().count() != origins.size()) {
            throw new IllegalArgumentException("同一Cookie主机只能配置一个网页来源");
        }
        if (properties.secureCookie()) {
            if (!"__Host-nexus_rt".equals(properties.cookieName()) || origins.stream().anyMatch(o -> !o.startsWith("https://"))) {
                throw new IllegalArgumentException("Web生产会话需要HTTPS和固定Host Cookie");
            }
        } else {
            boolean local = Set.of(environment.getActiveProfiles()).contains("local");
            if (!local || !"nexus_rt_dev".equals(properties.cookieName()) || origins.stream().anyMatch(o -> {
                String host = URI.create(o).getHost();
                return !("127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host));
            })) throw new IllegalArgumentException("非Secure Cookie仅允许local配置的回环来源");
        }
    }

    private static String checkedOrigin(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Web来源配置无效"); }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null ||
                uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
                (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Web来源必须为精确的协议、主机和可选端口");
        }
        return value;
    }

    public void check(HttpServletRequest request) {
        if (java.util.Collections.list(request.getHeaders("Origin")).size() != 1 ||
                !"cookie".equals(request.getHeader(HEADER)) || !origins.contains(request.getHeader("Origin")) ||
                (request.getHeader("Sec-Fetch-Site") != null && !"same-origin".equals(request.getHeader("Sec-Fetch-Site")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "WEB_ORIGIN_REJECTED");
        }
    }

    public String token(HttpServletRequest request) {
        String value = null;
        if (request.getCookies() != null) for (var cookie : request.getCookies()) {
            if (properties.cookieName().equals(cookie.getName())) {
                if (value != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AMBIGUOUS_SESSION_COOKIE");
                value = cookie.getValue();
            }
        }
        return value;
    }

    public void set(HttpServletResponse response, String value) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(value).build().toString());
    }
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("").maxAge(Duration.ZERO).build().toString());
    }
    private ResponseCookie.ResponseCookieBuilder cookie(String value) {
        return ResponseCookie.from(properties.cookieName(), value).path("/").httpOnly(true)
                .secure(properties.secureCookie()).sameSite("Strict");
    }
}
