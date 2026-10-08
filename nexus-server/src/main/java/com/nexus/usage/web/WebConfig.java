package com.nexus.usage.web;

import com.nexus.usage.service.UsageRecordService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ObjectProvider<UsageRecordService> usageRecordService;

    public WebConfig(ObjectProvider<UsageRecordService> usageRecordService) {
        this.usageRecordService = usageRecordService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // MVC 切片测试不加载 Usage 写入 Service；完整应用中才注册机器 API 埋点。
        UsageRecordService recorder = usageRecordService.getIfAvailable();
        if (recorder != null) {
            registry.addInterceptor(new UsageInterceptor(recorder))
                    .addPathPatterns("/v1/**")
                    .excludePathPatterns("/v1/health");
        }
    }
}
