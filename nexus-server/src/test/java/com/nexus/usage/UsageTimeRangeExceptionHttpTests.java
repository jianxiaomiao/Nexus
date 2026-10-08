package com.nexus.usage;

import com.nexus.common.web.ApiResponse;
import com.nexus.common.web.GlobalExceptionHandler;
import com.nexus.usage.exception.InvalidUsageTimeRangeException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class UsageTimeRangeExceptionHttpTests {
    @Test
    void invalidCustomRangeReturnsTheUsualBadRequestBody() throws Exception {
        MockMvc mockMvc = standaloneSetup(new InvalidRangeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/test/usage/invalid-range"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_USAGE_TIME_RANGE"))
                .andExpect(jsonPath("$.message").value("自定义时间范围必须传入customStartTime、customEndTime"));
    }

    @RestController
    static class InvalidRangeController {
        @GetMapping("/test/usage/invalid-range")
        ApiResponse<Void> throwInvalidRange() {
            throw new InvalidUsageTimeRangeException("自定义时间范围必须传入customStartTime、customEndTime");
        }
    }
}
