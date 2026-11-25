package com.nexus.supportchat;

import com.nexus.auth.exception.AccountForbiddenException;
import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.token.AccessTokenIdentity;
import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.ApiResponse;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
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
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final UserMapper userMapper;
    private final ObjectMapper json;
    private final URI origin;
    private final String credential;
    private final boolean profileEnabled;
    private final byte[] verifierDigest;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    NexusChatController(BearerUserIdResolver identities, UserMapper userMapper, ObjectMapper json,
                        @Value("${NEXUS_CHAT_ORIGIN:}") String origin,
                        @Value("${NEXUS_CHAT_CREDENTIAL:}") String credential,
                        @Value("${NEXUS_CHAT_VERIFY_KEY_SHA256:}") String verifyKeySha256,
                        @Value("${NEXUS_CHAT_PROFILE_ENABLED:false}") boolean profileEnabled) {
        this.identities = identities;
        this.userMapper = userMapper;
        this.json = json;
        this.credential = credential;
        this.profileEnabled = profileEnabled;
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
        var session = new SessionReference(identity.userId(), identity.sessionId());
        var payload = profileEnabled && origin != null
                ? new LaunchGrantRequest(session.userId(), session.sessionId(), resolveProfile(session.userId()))
                : session;
        Grant grant = request("/integrations/nexus/launch-grants", payload, 201, Grant.class);
        if (grant.grant() == null || !grant.grant().matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>("SUCCESS", null, new Launch(origin.resolve("/launch/nexus").toString(), grant.grant())));
    }

    private CustomerProfile resolveProfile(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return null;
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("emailVerified", user.getEmailVerifiedAt() != null);
        if (user.getCreatedAt() != null) attrs.put("registeredAt", user.getCreatedAt().toLocalDate().toString());
        return new CustomerProfile(user.getDisplayName(), user.getEmail(), null, null, attrs);
    }

    @GetMapping("/api/support/chat/unread")
    ResponseEntity<ApiResponse<Unread>> unread(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        long userId = identities.resolve(auth);
        Unread result = request("/integrations/nexus/unread-summary", new UnreadQuery(userId), 200, Unread.class);
        if (result.unread() == null || result.unread() < 0 || result.unread() > 9_007_199_254_740_991L) throw unavailable();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ApiResponse<>("SUCCESS", null, result));
    }

    private <T> T request(String path, Object input, int successStatus, Class<T> responseType) {
        if (origin == null) throw unavailable();
        var request = HttpRequest.newBuilder(origin.resolve(path))
                .timeout(Duration.ofSeconds(4)).header("Authorization", "Bearer " + credential)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)))
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != successStatus || response.body().length() > 1024) throw unavailable();
            T result = json.readValue(response.body(), responseType);
            if (result == null) throw unavailable();
            return result;
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
    record CustomerProfile(String displayName, String email, String phone, String avatarUrl, Map<String, Object> attributes) {}
    record LaunchGrantRequest(long userId, long sessionId, CustomerProfile profile) {}
    record Grant(String grant) {}
    record UnreadQuery(long userId) {}
    record Unread(Long unread) {}
    record Launch(String action, String grant) {}
}
