package com.nexus.webhook.service;

import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import com.nexus.webhook.crypto.EncryptedSecret;
import com.nexus.webhook.crypto.WebhookSecretCrypto;
import com.nexus.webhook.dto.CreateWebhookRequest;
import com.nexus.webhook.dto.RotateWebhookSecretRequest;
import com.nexus.webhook.dto.UpdateWebhookRequest;
import com.nexus.webhook.entity.WebhookSecret;
import com.nexus.webhook.exception.WebhookAlreadyExistsException;
import com.nexus.webhook.exception.WebhookSecretRotationConflictException;
import com.nexus.webhook.mapper.ApplicationWebhookMapper;
import com.nexus.webhook.mapper.WebhookSecretMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = "nexus.webhook.scheduling.enabled=false")
class ApplicationWebhookServiceIntegrationTests {
    @Autowired ApplicationWebhookService service;
    @Autowired ApplicationWebhookMapper webhooks;
    @MockitoSpyBean WebhookSecretMapper secrets;
    @Autowired WebhookSecretCrypto crypto;
    @Autowired UserMapper users;
    @Autowired ApplicationMapper applications;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean WebhookEndpointValidator endpoints;

    User owner;
    Application app;

    @BeforeEach
    void setup() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        owner = new User();
        owner.setEmail(UUID.randomUUID() + "@example.test");
        owner.setDisplayName("webhook management test");
        owner.setPasswordHash("test-only-placeholder");
        users.insert(owner);
        app = new Application();
        app.setOwnerUserId(owner.getId());
        app.setName("webhook-management-" + UUID.randomUUID());
        applications.insert(app);
        doAnswer(invocation -> invocation.getArgument(0)).when(endpoints).validate(any());
    }

    @AfterEach
    void cleanup() {
        if (app != null && app.getId() != null) {
            jdbc.update("DELETE FROM webhook_outbox WHERE application_id = ?", app.getId());
            jdbc.update("DELETE s FROM webhook_secrets s JOIN application_webhooks w ON w.id=s.webhook_id WHERE w.application_id=?", app.getId());
            jdbc.update("DELETE FROM application_webhooks WHERE application_id = ?", app.getId());
            applications.deleteById(app.getId());
        }
        if (owner != null && owner.getId() != null) users.deleteById(owner.getId());
    }

    @Test
    void createQueryUpdateAndRotateKeepSecretOneTimeAndEncrypted() {
        var created = service.createMine(owner.getId(), app.getId(),
                new CreateWebhookRequest("https://hooks.example.test/nexus"));
        assertNotNull(created.secret());
        assertEquals(43, created.secret().length());
        assertEquals(1L, created.currentSecretVersion());

        var queried = service.queryMine(owner.getId(), app.getId());
        assertEquals(created.id(), queried.id());
        assertEquals(1L, queried.currentSecretVersion());

        WebhookSecret stored = secrets.selectCurrentSecretsByWebhookId(created.id(),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).getFirst();
        byte[] decrypted = crypto.decrypt(new EncryptedSecret(stored.getSecretCiphertext(), stored.getSecretNonce(),
                stored.getEncryptionAlgorithm(), stored.getEncryptionKeyVersion()));
        assertArrayEquals(Base64.getUrlDecoder().decode(created.secret()), decrypted);
        assertFalse(new String(stored.getSecretCiphertext(), java.nio.charset.StandardCharsets.UTF_8)
                .contains(created.secret()));

        var paused = service.updateMine(owner.getId(), app.getId(), new UpdateWebhookRequest(null, 1));
        assertEquals(1, paused.status());
        var rotated = service.rotateSecretMine(owner.getId(), app.getId(),
                new RotateWebhookSecretRequest(1L));
        assertEquals(2L, rotated.webhook().currentSecretVersion());
        assertNotEquals(created.secret(), rotated.secret());
        assertThrows(WebhookSecretRotationConflictException.class, () ->
                service.rotateSecretMine(owner.getId(), app.getId(), new RotateWebhookSecretRequest(1L)));
        assertEquals(1, secrets.selectCurrentSecretsByWebhookId(created.id(),
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).size());
    }

    @Test
    void duplicateCreateIsAConflictAndDoesNotAddAnotherSecret() {
        var first = service.createMine(owner.getId(), app.getId(),
                new CreateWebhookRequest("https://hooks.example.test/one"));
        assertThrows(WebhookAlreadyExistsException.class, () -> service.createMine(owner.getId(), app.getId(),
                new CreateWebhookRequest("https://hooks.example.test/two")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM application_webhooks WHERE application_id=?",
                Integer.class, app.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM webhook_secrets WHERE webhook_id=?",
                Integer.class, first.id()));
    }

    @Test
    void secretInsertFailureRollsBackWebhookConfiguration() {
        doThrow(new IllegalStateException("injected secret insert failure"))
                .when(secrets).insert(any(WebhookSecret.class));

        assertThrows(IllegalStateException.class, () -> service.createMine(owner.getId(), app.getId(),
                new CreateWebhookRequest("https://hooks.example.test/rollback")));
        assertNull(webhooks.selectByApplicationId(app.getId()));
    }
}
