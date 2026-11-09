package com.nexus.webhook.controller;

import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.webhook.dto.*;
import com.nexus.webhook.exception.WebhookAlreadyExistsException;
import com.nexus.webhook.exception.WebhookSecretRotationConflictException;
import com.nexus.webhook.service.ApplicationWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApplicationWebhookController.class)
class ApplicationWebhookControllerTests {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @MockitoBean BearerUserIdResolver users;
    @MockitoBean ApplicationWebhookService service;

    private static final Instant CREATED = Instant.parse("2026-11-09T01:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-11-09T02:00:00Z");

    @Test
    void queryReturnsNullWhenApplicationHasNoWebhook() throws Exception {
        when(users.resolve("Bearer token")).thenReturn(7L);
        when(service.queryMine(7L, 11L)).thenReturn(null);

        mockMvc.perform(get("/api/application/11/webhook").header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void createReturnsSecretOnlyWithCreationResponse() throws Exception {
        var request = new CreateWebhookRequest("https://hooks.example.com/nexus");
        when(users.resolve("Bearer token")).thenReturn(7L);
        when(service.createMine(7L, 11L, request)).thenReturn(new CreatedApplicationWebhookResponse(
                21L, 11L, request.url(), 0, 1L, CREATED, CREATED, "one-time-secret"));

        mockMvc.perform(post("/api/application/11/webhook")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.currentSecretVersion").value(1))
                .andExpect(jsonPath("$.data.secret").value("one-time-secret"));
    }

    @Test
    void duplicateCreationReturnsConflict() throws Exception {
        var request = new CreateWebhookRequest("https://hooks.example.com/nexus");
        when(users.resolve("Bearer token")).thenReturn(7L);
        when(service.createMine(7L, 11L, request))
                .thenThrow(new WebhookAlreadyExistsException(new IllegalStateException()));

        mockMvc.perform(post("/api/application/11/webhook")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_ALREADY_EXISTS"));
    }

    @Test
    void updateSupportsUrlOrStatusAndRejectsInvalidStatusInDomain() throws Exception {
        var request = new UpdateWebhookRequest(null, 1);
        var response = new ApplicationWebhookResponse(21L, 11L, "https://hooks.example.com/nexus",
                1, 1L, CREATED, UPDATED);
        when(users.resolve("Bearer token")).thenReturn(7L);
        when(service.updateMine(7L, 11L, request)).thenReturn(response);

        mockMvc.perform(put("/api/application/11/webhook")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.secret").doesNotExist());
    }

    @Test
    void rotateRequiresExpectedVersionAndTranslatesConflict() throws Exception {
        when(users.resolve("Bearer token")).thenReturn(7L);

        mockMvc.perform(post("/api/application/11/webhook/rotate-secret")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);

        var request = new RotateWebhookSecretRequest(1L);
        when(service.rotateSecretMine(7L, 11L, request))
                .thenThrow(new WebhookSecretRotationConflictException());
        mockMvc.perform(post("/api/application/11/webhook/rotate-secret")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SECRET_ROTATION_CONFLICT"));
    }
}
