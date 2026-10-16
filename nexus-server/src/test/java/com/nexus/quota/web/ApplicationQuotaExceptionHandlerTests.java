package com.nexus.quota.web;

import com.nexus.common.web.GlobalExceptionHandler;
import com.nexus.quota.exception.ApplicationQuotaExhaustedException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApplicationQuotaExceptionHandlerTests {
    @Test void exhaustedQuotaReturns429AndStableCode() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SampleController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/quota-test"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("APPLICATION_QUOTA_EXHAUSTED"));
    }

    @RestController
    static class SampleController {
        @GetMapping("/quota-test")
        public void invoke() { throw new ApplicationQuotaExhaustedException(); }
    }
}
