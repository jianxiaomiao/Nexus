package com.nexus.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.net.URI;
import java.util.Set;

@Configuration
class SupportIntegrationConfig {
    @Bean
    SupportDestination supportDestination(
            @Value("${nexus.support.flowops.integration-id:}") String id,
            @Value("${nexus.support.flowops.endpoint:}") String endpoint,
            @Value("${nexus.support.flowops.credential:}") String credential) {
        if (id.isBlank() && endpoint.isBlank() && credential.isBlank()) {
            return new SupportDestination("", null, "");
        }
        if (!id.matches("[A-Za-z0-9_-]{1,64}") || !credential.matches("[A-Za-z0-9_-]{1,64}\\.[A-Za-z0-9_-]{43}")) {
            throw new IllegalStateException("Invalid FlowOps integration ID or credential");
        }
        URI uri = URI.create(endpoint);
        String host = uri.getHost();
        boolean loopback = host != null && Set.of("127.0.0.1", "[::1]", "localhost").contains(host);
        boolean transportAllowed = "https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && loopback;
        if (host == null || !transportAllowed || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !uri.getPath().endsWith("/integration/v1/tickets")) {
            throw new IllegalStateException("Invalid FlowOps ticket endpoint");
        }
        return new SupportDestination(id, uri, credential);
    }

    @Bean
    ThreadPoolTaskScheduler supportTicketScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("support-ticket-");
        return scheduler;
    }
}

final class SupportDestination {
    final String id;
    final URI endpoint;
    private final String credential;

    SupportDestination(String id, URI endpoint, String credential) {
        this.id = id;
        this.endpoint = endpoint;
        this.credential = credential;
    }

    String authorization() {
        return "Bearer " + credential;
    }

    boolean enabled() {
        return endpoint != null;
    }
}
