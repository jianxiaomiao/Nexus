package com.nexus.common.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RequestFailureLoggingFilterConfig {
    @Bean
    FilterRegistrationBean<RequestFailureLoggingFilter> requestFailureLoggingFilter() {
        FilterRegistrationBean<RequestFailureLoggingFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestFailureLoggingFilter());
        registration.addUrlPatterns("/v1/*");
        registration.addUrlPatterns("/api/*");
        registration.setOrder(10); // 必须早于机器认证 Filter，才能看到它直接返回的 401/403。
        return registration;
    }
}
