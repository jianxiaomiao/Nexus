package com.nexus.job.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScreenshotJobProperties.class)
public class ScreenshotJobConfig {
}
