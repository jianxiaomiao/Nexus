package com.nexus.supportchat;

import com.nexus.auth.token.AccessTokenIdentity;
import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NexusChatControllerTests {
    private static final String AUTHORIZATION = "Bearer session-token";
    private final JsonMapper json = new JsonMapper();
    private final BearerUserIdResolver identities = mock(BearerUserIdResolver.class);
    private final UserMapper users = mock(UserMapper.class);

    @Test
    void leavesProfileOutOfLegacyLaunchRequest() throws IOException {
        var payload = launch(false);
        assertEquals(json.readTree("""
                {"userId":7,"sessionId":11}
                """), payload);
        verifyNoInteractions(users);
    }

    @Test
    void enabledProfileUsesOnlyTheCurrentUsersSupportFields() throws IOException {
        var user = new User();
        user.setDisplayName("验收客户");
        user.setEmail("customer@example.test");
        user.setCreatedAt(LocalDateTime.parse("2026-11-24T23:30:00"));
        user.setEmailVerifiedAt(LocalDateTime.parse("2026-11-25T08:00:00"));
        when(users.selectById(7L)).thenReturn(user);

        assertEquals(json.readTree("""
                {"userId":7,"sessionId":11,"profile":{
                    "displayName":"验收客户","email":"customer@example.test","phone":null,"avatarUrl":null,
                    "attributes":{"emailVerified":true,"registeredAt":"2026-11-24"}
                }}
                """), launch(true));
    }

    private JsonNode launch(boolean profileEnabled) throws IOException {
        when(identities.resolveIdentity(AUTHORIZATION)).thenReturn(new AccessTokenIdentity(7L, 11L));
        var payload = new AtomicReference<JsonNode>();
        var receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var grant = "g".repeat(43);
        receiver.createContext("/integrations/nexus/launch-grants", exchange -> {
            try (exchange) {
                payload.set(json.readTree(exchange.getRequestBody().readAllBytes()));
                var response = ("{\"grant\":\"" + grant + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        receiver.start();
        try {
            String origin = "http://127.0.0.1:" + receiver.getAddress().getPort();
            var controller = new NexusChatController(identities, users, json, origin,
                    "0".repeat(36) + "." + "x".repeat(43), "0".repeat(64), profileEnabled);
            var response = controller.launch(AUTHORIZATION);
            assertEquals(200, response.getStatusCode().value());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertEquals(new NexusChatController.Launch(origin + "/launch/nexus", grant), response.getBody().data());
            return payload.get();
        } finally {
            receiver.stop(0);
        }
    }
}
