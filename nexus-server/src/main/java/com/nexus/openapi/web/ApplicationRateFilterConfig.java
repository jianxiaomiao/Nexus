package com.nexus.openapi.web;

import com.nexus.openapi.ratelimit.ApplicationRateLimiter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class ApplicationRateFilterConfig {
    @Bean
    FilterRegistrationBean<ApplicationRateFilter> applicationRateFilterRegistration(
            ApplicationRateLimiter rateLimiter,
            MachineIdentityResolver identityResolver,
            ObjectMapper objectMapper) {
        ApplicationRateFilter filter =
                new ApplicationRateFilter(rateLimiter, identityResolver, objectMapper);

        FilterRegistrationBean<ApplicationRateFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/v1/*"); // 只覆盖机器 Open API；管理端 /api/* 不经过此 Filter。
        registration.setOrder(30);
        registration.setAsyncSupported(true);
        return registration;
    }
}
