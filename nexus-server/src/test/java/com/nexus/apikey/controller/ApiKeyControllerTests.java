package com.nexus.apikey.controller;

import com.nexus.apikey.dto.ApiKeyResponse;
import com.nexus.apikey.dto.CreateApiKeyRequest;
import com.nexus.apikey.dto.CreateApiKeyResponse;
import com.nexus.apikey.dto.DeleteApiKeyRequest;
import com.nexus.apikey.dto.UpdateApiKeyRequest;
import com.nexus.apikey.exception.ApiKeyNameAlreadyExistsException;
import com.nexus.apikey.exception.ApiKeyNotFoundException;
import com.nexus.apikey.service.ApiKeyService;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.web.BearerUserIdResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiKeyController.class)
class ApiKeyControllerTests {
    private static final String BASE_PATH = "/api/apiKey";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private BearerUserIdResolver bearerUserIdResolver;

    @Test
    void createReturnsFullKeyOnlyInCreationResponse() throws Exception {
        CreateApiKeyRequest request = new CreateApiKeyRequest("key", 7L);
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(apiKeyService.createMyApiKey(42L, request)).thenReturn(new CreateApiKeyResponse(
                9L, 7L, "key", "public-id", "masked-preview", "complete-key", 0,
                LocalDateTime.of(2026, 10, 4, 12, 0)));

        mockMvc.perform(post(BASE_PATH + "/create")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.apiKey").value("complete-key"))
                .andExpect(jsonPath("$.data.publicId").value("public-id"))
                .andExpect(jsonPath("$.data.keyPreview").value("masked-preview"))
                .andExpect(jsonPath("$.data.secretHash").doesNotExist());

        verify(apiKeyService).createMyApiKey(42L, request);
    }

    @Test
    void listReturnsOnlySafeKeyFields() throws Exception {
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(apiKeyService.listMyApiKeys(42L, 7L, 1, 10, null)).thenReturn(new com.nexus.common.web.ListPage<>(1, 1, 10, List.of(
                new ApiKeyResponse(9L, 7L, "key", "public-id", "masked-preview", 0,
                        LocalDateTime.of(2026, 10, 4, 12, 0), LocalDateTime.of(2026, 10, 4, 12, 0)))));

        mockMvc.perform(get(BASE_PATH + "/7")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(9))
                .andExpect(jsonPath("$.data.records[0].keyPreview").value("masked-preview"))
                .andExpect(jsonPath("$.data.records[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].secretHash").doesNotExist());

        verify(apiKeyService).listMyApiKeys(42L, 7L, 1, 10, null);
    }

    @Test
    void updateUsesBodyIdsAndReturnsSafeResponse() throws Exception {
        UpdateApiKeyRequest request = new UpdateApiKeyRequest("renamed", 7L, 9L, 1);
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(apiKeyService.updateMyApiKey(42L, request)).thenReturn(new ApiKeyResponse(
                9L, 7L, "renamed", "public-id", "masked-preview", 1, null, null));

        mockMvc.perform(put(BASE_PATH)
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("renamed"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());

        verify(apiKeyService).updateMyApiKey(42L, request);
    }

    @Test
    void deleteUsesBodyIds() throws Exception {
        DeleteApiKeyRequest request = new DeleteApiKeyRequest(7L, 9L);
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);

        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(apiKeyService).deleteMyApiKey(42L, request);
    }

    @Test
    void invalidBodyIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/create")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApiKeyRequest(" ", 7L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(put(BASE_PATH)
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateApiKeyRequest("key", 7L, 9L, 2))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.status").exists());
        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeleteApiKeyRequest(7L, 0L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.apiKeyId").exists());

        verifyNoInteractions(apiKeyService);
    }

    @Test
    void missingTokenCannotListKeys() throws Exception {
        when(bearerUserIdResolver.resolve(null)).thenThrow(new InvalidAccessTokenException());

        mockMvc.perform(get(BASE_PATH + "/7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        verifyNoInteractions(apiKeyService);
    }

    @Test
    void serviceErrorsKeepTheirHttpMeaning() throws Exception {
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        CreateApiKeyRequest createRequest = new CreateApiKeyRequest("key", 7L);
        when(apiKeyService.createMyApiKey(42L, createRequest))
                .thenThrow(new ApiKeyNameAlreadyExistsException(new IllegalStateException("duplicate")));
        when(apiKeyService.listMyApiKeys(42L, 7L, 1, 10, null)).thenThrow(new ApplicationNotFoundException());
        DeleteApiKeyRequest deleteRequest = new DeleteApiKeyRequest(7L, 9L);
        doThrow(new ApiKeyNotFoundException()).when(apiKeyService).deleteMyApiKey(42L, deleteRequest);

        mockMvc.perform(post(BASE_PATH + "/create")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("API_KEY_NAME_ALREADY_EXISTS"));
        mockMvc.perform(get(BASE_PATH + "/7")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_KEY_NOT_FOUND"));
    }
}
