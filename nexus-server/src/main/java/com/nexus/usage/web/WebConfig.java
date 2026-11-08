package com.nexus.usage.web;

import com.nexus.usage.metrics.UsageRecordMetrics;
import com.nexus.usage.service.UsageRecordService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.time.Clock;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ObjectProvider<UsageRecordService> usageRecordService;
    private final ObjectProvider<UsageRecordMetrics> usageRecordMetrics;
    private final ObjectProvider<Clock> clock;

    public WebConfig(ObjectProvider<UsageRecordService> usageRecordService,
                     ObjectProvider<UsageRecordMetrics> usageRecordMetrics, ObjectProvider<Clock> clock) {
        this.usageRecordService = usageRecordService;
        this.usageRecordMetrics = usageRecordMetrics;
        this.clock = clock;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // MVC 切片测试不加载 Usage 写入 Service；完整应用中才注册机器 API 埋点。
        UsageRecordService recorder = usageRecordService.getIfAvailable();
        UsageRecordMetrics metrics = usageRecordMetrics.getIfAvailable();
        if (recorder != null && metrics != null) {
            registry.addInterceptor(new UsageInterceptor(recorder, metrics, clock.getObject()))
                    .addPathPatterns("/v1/**")
                    .excludePathPatterns("/v1/health");
        }
    }
}
