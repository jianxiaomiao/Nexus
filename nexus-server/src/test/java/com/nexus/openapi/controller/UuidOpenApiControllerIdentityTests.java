package com.nexus.openapi.controller;

import com.nexus.common.web.GlobalExceptionHandler;
import com.nexus.openapi.web.MachineIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UuidOpenApiControllerIdentityTests {
    // 不注册认证 Filter，直接验证 Controller 自身不会把请求头当作已认证身份。
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new UuidOpenApiController(new MachineIdentityResolver()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void authorizationHeaderWithoutRequestIdentityIsRejected() throws Exception {
        mvc.perform(get("/v1/utils/uuid")
                        .header("Authorization", "ApiKey credential-without-filter"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "ApiKey realm=\"nexus-openapi\""))
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY_CREDENTIAL"))
                .andExpect(jsonPath("$.data.uuid").doesNotExist());
    }

    @Test
    void wrongRequestIdentityTypeIsRejected() throws Exception {
        mvc.perform(get("/v1/utils/uuid")
                        .requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, "not-an-identity"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY_CREDENTIAL"))
                .andExpect(jsonPath("$.data.uuid").doesNotExist());
    }
}
