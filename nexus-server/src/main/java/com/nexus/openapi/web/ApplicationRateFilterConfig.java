package com.nexus.openapi.web;

import com.nexus.openapi.ratelimit.ApplicationRateLimiter;
import com.nexus.openapi.ratelimit.ApplicationRateMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class ApplicationRateFilterConfig {
    @Bean
    ApplicationRateMetrics applicationRateMetrics(MeterRegistry meterRegistry) {
        return new ApplicationRateMetrics(meterRegistry);
    }

    @Bean
    FilterRegistrationBean<ApplicationRateFilter> applicationRateFilterRegistration(
            ApplicationRateLimiter rateLimiter,
            MachineIdentityResolver identityResolver,
            ObjectMapper objectMapper,
            ApplicationRateMetrics rateMetrics) {
        ApplicationRateFilter filter =
                new ApplicationRateFilter(
                        rateLimiter, identityResolver, objectMapper, rateMetrics);

        FilterRegistrationBean<ApplicationRateFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/v1/*"); // 只覆盖机器 Open API；管理端 /api/* 不经过此 Filter。
        registration.setOrder(30);
        registration.setAsyncSupported(true);
        return registration;
    }
}
