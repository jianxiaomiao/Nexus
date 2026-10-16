package com.nexus.screenshot.controller;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.common.web.GlobalExceptionHandler;
import com.nexus.openapi.web.MachineIdentityResolver;
import com.nexus.screenshot.exception.ScreenshotException;
import com.nexus.screenshot.service.ScreenshotResult;
import com.nexus.screenshot.service.ScreenshotService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScreenshotOpenApiControllerTests {
    private static final String URL = "https://blog.csdn.net/article";
    private final ScreenshotService service = mock(ScreenshotService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ScreenshotOpenApiController(new MachineIdentityResolver(), service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    @Test
    void missingOrWrongIdentityNeverStartsCapture() throws Exception {
        mvc.perform(post("/v1/web/screenshot").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + URL + "\"}")
                        .header("Authorization", "ApiKey not-a-request-identity"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_API_KEY_CREDENTIAL"));
        mvc.perform(post("/v1/web/screenshot").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + URL + "\"}")
                        .requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, "wrong type"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test
    void successIsUnwrappedPngWithNoStoreAndBlockedResourceCount() throws Exception {
        byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        when(service.capture(new ApiKeyIdentity(1L, 2L), URL)).thenReturn(new ScreenshotResult(png, 2));
        mvc.perform(post("/v1/web/screenshot").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + URL + "\"}")
                        .requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, new ApiKeyIdentity(1L, 2L)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(content().bytes(png))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("X-Nexus-Blocked-Resources", "2"))
                .andExpect(header().string("X-Nexus-Resource-Limit-Reached", "false"));
    }
    @Test
    void partialPngIs200WithExplicitQuotaHeader() throws Exception {
        when(service.capture(new ApiKeyIdentity(1L, 2L), URL)).thenReturn(new ScreenshotResult(new byte[]{1, 2}, 7, true));
        mvc.perform(post("/v1/web/screenshot").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + URL + "\"}")
                        .requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, new ApiKeyIdentity(1L, 2L)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Nexus-Resource-Limit-Reached", "true"))
                .andExpect(header().string("X-Nexus-Blocked-Resources", "7"));
    }
    @Test
    void invalidBodyIsJson400WithoutCapture() throws Exception {
        mvc.perform(post("/v1/web/screenshot").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}")
                        .requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, new ApiKeyIdentity(1L, 2L)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(service);
    }
    @Test
    void failuresHaveStableJsonCodesAndStatuses() throws Exception {
        var kinds = ScreenshotException.Kind.values();
        int[] statuses = {400, 502, 504, 422, 503, 503};
        for (int index = 0; index < kinds.length; index++) {
            var error = new ScreenshotException(kinds[index]);
            doThrow(error).when(service).capture(new ApiKeyIdentity(1L, 2L), URL);
            mvc.perform(post("/v1/web/screenshot").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + URL + "\"}")
                            .requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, new ApiKeyIdentity(1L, 2L)))
                    .andExpect(status().is(statuses[index])).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value(error.code()));
        }
    }
}
