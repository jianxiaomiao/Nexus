package com.nexus.webExtract.controller;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.common.web.GlobalExceptionHandler;
import com.nexus.openapi.web.MachineIdentityResolver;
import com.nexus.webExtract.dto.WebExtractResponse;
import com.nexus.webExtract.exception.WebPageFetchException;
import com.nexus.webExtract.service.WebExtractService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebExtractOpenApiControllerTests {
    private static final String URL = "https://blog.csdn.net/user/article/details/123";
    private final WebExtractService service = mock(WebExtractService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new WebExtractOpenApiController(new MachineIdentityResolver(), service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void missingMachineIdentityNeverFetchesWebpage() throws Exception {
        mvc.perform(post("/v1/web/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY_CREDENTIAL"));
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedRequestReturnsTextAndHtml() throws Exception {
        when(service.extract(URL)).thenReturn(new WebExtractResponse("标题", "正文", "<p>正文</p>"));
        mvc.perform(post("/v1/web/extract")
                        .requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, new ApiKeyIdentity(1L, 2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.textContent").value("正文"))
                .andExpect(jsonPath("$.data.contentHtml").value("<p>正文</p>"));
    }

    @Test
    void upstreamFailureHasStableApiError() throws Exception {
        when(service.extract(URL)).thenThrow(new WebPageFetchException("目标页面暂时无法访问"));
        mvc.perform(post("/v1/web/extract")
                        .requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, new ApiKeyIdentity(1L, 2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("WEB_PAGE_FETCH_FAILED"));
    }
}
