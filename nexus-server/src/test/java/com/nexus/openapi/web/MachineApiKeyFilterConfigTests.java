package com.nexus.openapi.web;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyAuthenticator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class MachineApiKeyFilterConfigTests {
    @Test
    void machineAuthRunsAfterFailureLoggingFilter() {
        var registration = new MachineApiKeyFilterConfig()
                .machineApiKeyFilter(mock(ApiKeyAuthenticator.class), mock(ObjectMapper.class));

        assertEquals(20, registration.getOrder());
    }
}
