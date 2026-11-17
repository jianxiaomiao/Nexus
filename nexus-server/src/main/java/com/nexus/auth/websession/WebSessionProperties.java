package com.nexus.auth.websession;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.util.List;

@ConfigurationProperties(prefix = "nexus.auth.web")
public record WebSessionProperties(
        @DefaultValue("true") boolean secureCookie,
        @DefaultValue("__Host-nexus_rt") String cookieName,
        @DefaultValue List<String> allowedOrigins) {
    public WebSessionProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
