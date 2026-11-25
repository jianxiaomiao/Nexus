package com.nexus.supportchat;

import com.nexus.auth.exception.AccountForbiddenException;
import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.token.AccessTokenIdentity;
import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
class NexusChatController {
    private final BearerUserIdResolver identities;
    private final ObjectMapper json;
    private final URI origin;
    private final String credential;
    private final byte[] verifierDigest;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    NexusChatController(BearerUserIdResolver identities, ObjectMapper json,
                        @Value("${NEXUS_CHAT_ORIGIN:}") String origin,
                        @Value("${NEXUS_CHAT_CREDENTIAL:}") String credential,
                        @Value("${NEXUS_CHAT_VERIFY_KEY_SHA256:}") String verifyKeySha256) {
        this.identities = identities;
        this.json = json;
        this.credential = credential;
        if (origin.isEmpty() && credential.isEmpty() && verifyKeySha256.isEmpty()) {
            this.origin = null;
            this.verifierDigest = new byte[0];
            return;
        }
        this.origin = requireOrigin(origin);
        if (!credential.matches("[a-f0-9-]{36}\\.[A-Za-z0-9_-]{43}") || !verifyKeySha256.matches("[A-Fa-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid Nexus chat credential configuration");
        }
        this.verifierDigest = HexFormat.of().parseHex(verifyKeySha256);
    }

    @PostMapping("/api/support/chat/launch")
    ResponseEntity<ApiResponse<Launch>> launch(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        AccessTokenIdentity identity = identities.resolveIdentity(auth);
        if (origin == null) throw unavailable();
        var request = HttpRequest.newBuilder(origin.resolve("/integrations/nexus/launch-grants"))
                .timeout(Duration.ofSeconds(4)).header("Authorization", "Bearer " + credential)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        new SessionReference(identity.userId(), identity.sessionId()))))
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 || response.body().length() > 1024) throw unavailable();
            Grant grant = json.readValue(response.body(), Grant.class);
            if (grant == null || grant.grant() == null || !grant.grant().matches("[A-Za-z0-9_-]{43}")) throw unavailable();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new ApiResponse<>("SUCCESS", null, new Launch(origin.resolve("/launch/nexus").toString(), grant.grant())));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | tools.jackson.core.JacksonException failure) {
            throw unavailable();
        }
    }

    @PostMapping(value = "/api/internal/support/chat/session-status", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> verify(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                @Valid @RequestBody SessionReference reference) {
        verifyCredential(auth);
        try {
            identities.requireActive(new AccessTokenIdentity(reference.userId(), reference.sessionId()));
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } catch (InvalidAccessTokenException | AccountForbiddenException inactive) {
            return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResponse<Void>> failure(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(error.getReason(), "在线客服接入暂不可用", null));
    }

    private void verifyCredential(String auth) {
        String value = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
        if (verifierDigest.length == 0 || !value.matches("[A-Za-z0-9_-]{43}")
                || !MessageDigest.isEqual(verifierDigest, digest(value))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "CHAT_VERIFIER_REQUIRED");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static URI requireOrigin(String value) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host);
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getRawPath().isEmpty() || uri.getPort() == 0 || uri.getPort() > 65535
                || !("https".equals(uri.getScheme()) || loopback && "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("Invalid NewCrisp origin");
        }
        return uri;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "CHAT_UNAVAILABLE");
    }

    record SessionReference(@Positive long userId, @Positive long sessionId) {}
    record Grant(String grant) {}
    record Launch(String action, String grant) {}
}
