package com.nexus.webhook.dispatch;

import java.time.Clock;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookRetryPolicy.class)
public class WebhookClassificationConfig {
    @Bean
    WebhookResponseHandler webhookResponseHandler(WebhookRetryPolicy policy, Clock clock) {
        return new WebhookResponseHandler(policy, clock);
    }

    @Bean
    WebhookAttemptPreparer webhookAttemptPreparer(WebhookOutboxMapper mapper, PlatformTransactionManager manager,
            WebhookRetryPolicy policy) {
        return new WebhookAttemptPreparer(mapper, new TransactionTemplate(manager), policy.maxRetries());
    }

    @Bean
    WebhookExceptionClassifier webhookExceptionClassifier() { return new WebhookExceptionClassifier(); }
}
