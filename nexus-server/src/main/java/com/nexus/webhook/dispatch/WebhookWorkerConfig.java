package com.nexus.webhook.dispatch;

import com.nexus.webhook.mapper.WebhookOutboxMapper;
import java.time.Clock;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WebhookWorkerConfig {
    @Bean(destroyMethod = "close")
    WebhookHttpClient webhookHttpClient() { return new WebhookHttpClient(); }

    @Bean(destroyMethod = "close")
    WebhookWorkerImpl webhookWorker(WebhookLeaseManager leases, WebhookAttemptPreparer preparer,
            WebhookOutboxMapper outbox, com.nexus.webhook.mapper.ApplicationWebhookMapper configurations,
            com.nexus.webhook.mapper.WebhookSecretMapper secrets, WebhookHttpClient http,
            com.nexus.webhook.crypto.WebhookSecretCrypto crypto, com.nexus.webhook.crypto.WebhookSignatureService signatures,
            tools.jackson.databind.ObjectMapper json, WebhookResponseHandler responses,
            WebhookExceptionClassifier classifier, Clock clock) {
        return new WebhookWorkerImpl(leases, preparer, outbox, configurations, secrets, http, crypto, signatures,
                json, responses, classifier, clock);
    }

    @Bean
    WebhookDispatcher webhookDispatcher(WebhookOutboxMapper mapper,
                                         PlatformTransactionManager transactionManager,
                                         @Qualifier("webhookExecutor") ThreadPoolExecutor executor,
                                         @Qualifier("webhookPermits") Semaphore permits,
                                         Clock clock,
                                         @Value("${nexus.webhook.dispatch.lease-duration:60s}") String leaseDuration,
                                         WebhookWorker worker) {
        return new WebhookDispatcher(mapper, new TransactionTemplate(transactionManager), executor, permits,
                clock, DurationStyle.detectAndParse(leaseDuration), worker);
    }
}
