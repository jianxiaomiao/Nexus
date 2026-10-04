package com.nexus.openapi.web;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyAuthenticator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class MachineApiKeyFilterConfig {
    @Bean
    FilterRegistrationBean<MachineApiKeyFilter> machineApiKeyFilter(
            ApiKeyAuthenticator authenticator, ObjectMapper objectMapper) {
        FilterRegistrationBean<MachineApiKeyFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new MachineApiKeyFilter(authenticator, objectMapper));
        registration.addUrlPatterns("/v1/*"); // 只覆盖机器 Open API；管理端 /api/* 不经过此 Filter。
        // 不要再给 MachineApiKeyFilter 加 @Component，否则可能被自动注册到所有路径。
        return registration;
    }
}
