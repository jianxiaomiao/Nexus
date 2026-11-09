package com.nexus.webhook.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.webhook.crypto.EncryptedSecret;
import com.nexus.webhook.crypto.WebhookSecretCrypto;
import com.nexus.webhook.dto.*;
import com.nexus.webhook.entity.ApplicationWebhook;
import com.nexus.webhook.entity.WebhookSecret;
import com.nexus.webhook.exception.*;
import com.nexus.webhook.mapper.ApplicationWebhookMapper;
import com.nexus.webhook.mapper.WebhookSecretMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

@Service
public class ApplicationWebhookService {
    private final ApplicationMapper applications;
    private final ApplicationWebhookMapper webhooks;
    private final WebhookSecretMapper secrets;
    private final WebhookSecretCrypto crypto;
    private final WebhookEndpointValidator endpoints;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ApplicationWebhookService(ApplicationMapper applications,
                                     ApplicationWebhookMapper webhooks,
                                     WebhookSecretMapper secrets,
                                     WebhookSecretCrypto crypto,
                                     WebhookEndpointValidator endpoints,
                                     Clock clock,
                                     PlatformTransactionManager transactionManager) {
        this.applications = applications;
        this.webhooks = webhooks;
        this.secrets = secrets;
        this.crypto = crypto;
        this.endpoints = endpoints;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ApplicationWebhookResponse queryMine(long userId, long applicationId) {
        requireOwned(userId, applicationId);
        ApplicationWebhook webhook = webhooks.selectByApplicationId(applicationId);
        return webhook == null ? null : response(webhook, currentSecret(webhook.getId()).getSecretVersion());
    }

    public CreatedApplicationWebhookResponse createMine(long userId, long applicationId,
                                                         CreateWebhookRequest request) {
        requireOwned(userId, applicationId);
        String url = endpoints.validate(request.url());
        byte[] rawSecret = crypto.generateSecret();
        try {
            String displayed = crypto.encodeForDisplay(rawSecret);
            EncryptedSecret encrypted = crypto.encrypt(rawSecret);
            try {
                return transaction.execute(status -> createInTransaction(
                        userId, applicationId, url, displayed, encrypted));
            } catch (DuplicateKeyException exception) {
                throw new WebhookAlreadyExistsException(exception);
            }
        } finally {
            Arrays.fill(rawSecret, (byte) 0);
        }
    }

    public ApplicationWebhookResponse updateMine(long userId, long applicationId,
                                                   UpdateWebhookRequest request) {
        if (request.url() == null && request.status() == null) {
            throw new InvalidWebhookUpdateException("至少提供一个要更新的字段");
        }
        if (request.url() != null && request.url().isBlank()) {
            throw new InvalidWebhookUpdateException("回调 URL 不能为空");
        }
        if (request.status() != null && request.status() != 0 && request.status() != 1) {
            throw new InvalidWebhookUpdateException("Webhook 状态只能是 0 或 1");
        }
        requireOwned(userId, applicationId);
        String url = request.url() == null ? null : endpoints.validate(request.url());
        return transaction.execute(status -> {
            requireOwnedForShare(userId, applicationId);
            ApplicationWebhook webhook = webhooks.selectByApplicationIdForUpdate(applicationId);
            if (webhook == null) throw new WebhookNotFoundException();
            if (url != null) webhook.setUrl(url);
            if (request.status() != null) webhook.setStatus(request.status());
            webhook.setUpdatedAt(utcNow());
            if (webhooks.updateById(webhook) != 1) throw new WebhookNotFoundException();
            return response(webhook, currentSecret(webhook.getId()).getSecretVersion());
        });
    }

    public RotatedWebhookSecretResponse rotateSecretMine(long userId, long applicationId,
                                                          RotateWebhookSecretRequest request) {
        requireOwned(userId, applicationId);
        byte[] rawSecret = crypto.generateSecret();
        try {
            String displayed = crypto.encodeForDisplay(rawSecret);
            EncryptedSecret encrypted = crypto.encrypt(rawSecret);
            return transaction.execute(status -> rotateInTransaction(
                    userId, applicationId, request.currentSecretVersion(), displayed, encrypted));
        } finally {
            Arrays.fill(rawSecret, (byte) 0);
        }
    }

    private CreatedApplicationWebhookResponse createInTransaction(long userId, long applicationId,
                                                                   String url, String displayed,
                                                                   EncryptedSecret encrypted) {
        requireOwnedForShare(userId, applicationId);
        LocalDateTime now = utcNow();
        ApplicationWebhook webhook = new ApplicationWebhook();
        webhook.setApplicationId(applicationId);
        webhook.setUrl(url);
        webhook.setStatus(0);
        webhook.setCreatedAt(now);
        webhook.setUpdatedAt(now);
        webhooks.insert(webhook);

        WebhookSecret secret = newSecret(webhook.getId(), 1L, encrypted, now);
        secrets.insert(secret);
        return new CreatedApplicationWebhookResponse(webhook.getId(), applicationId, url, 0, 1L,
                now.toInstant(ZoneOffset.UTC), now.toInstant(ZoneOffset.UTC), displayed);
    }

    private RotatedWebhookSecretResponse rotateInTransaction(long userId, long applicationId,
                                                              long expectedVersion, String displayed,
                                                              EncryptedSecret encrypted) {
        requireOwnedForShare(userId, applicationId);
        ApplicationWebhook webhook = webhooks.selectByApplicationIdForUpdate(applicationId);
        if (webhook == null) throw new WebhookNotFoundException();
        LocalDateTime now = utcNow();
        List<WebhookSecret> current = secrets.selectCurrentSecretsByWebhookIdForUpdate(webhook.getId(), now);
        if (current.size() != 1) throw new IllegalStateException("Webhook must have exactly one current secret");
        WebhookSecret old = current.getFirst();
        if (!Long.valueOf(expectedVersion).equals(old.getSecretVersion())) {
            throw new WebhookSecretRotationConflictException();
        }
        if (secrets.moveCurrentToGrace(old.getId(), webhook.getId(), expectedVersion, now.plusHours(1)) != 1) {
            throw new WebhookSecretRotationConflictException();
        }
        long nextVersion = Math.addExact(expectedVersion, 1L);
        secrets.insert(newSecret(webhook.getId(), nextVersion, encrypted, now));
        webhook.setUpdatedAt(now);
        if (webhooks.updateById(webhook) != 1) throw new WebhookNotFoundException();
        return new RotatedWebhookSecretResponse(response(webhook, nextVersion), displayed);
    }

    private WebhookSecret newSecret(Long webhookId, long version, EncryptedSecret encrypted,
                                    LocalDateTime now) {
        WebhookSecret secret = new WebhookSecret();
        secret.setWebhookId(webhookId);
        secret.setSecretCiphertext(encrypted.ciphertext());
        secret.setSecretNonce(encrypted.nonce());
        secret.setEncryptionAlgorithm(encrypted.encryptionAlgorithm());
        secret.setEncryptionKeyVersion(encrypted.encryptionKeyVersion());
        secret.setSecretVersion(version);
        secret.setStatus(0);
        secret.setCreatedAt(now);
        return secret;
    }

    private void requireOwned(long userId, long applicationId) {
        if (applicationId <= 0 || applications.selectCount(Wrappers.<Application>lambdaQuery()
                .eq(Application::getId, applicationId)
                .eq(Application::getOwnerUserId, userId)
                .eq(Application::getIsDeleted, 0)) != 1) {
            throw new ApplicationNotFoundException();
        }
    }

    private void requireOwnedForShare(long userId, long applicationId) {
        if (applications.selectOwnedIdForShare(userId, applicationId) == null) {
            throw new ApplicationNotFoundException();
        }
    }

    private WebhookSecret currentSecret(Long webhookId) {
        List<WebhookSecret> current = secrets.selectCurrentSecretsByWebhookId(webhookId, utcNow());
        if (current.size() != 1) throw new IllegalStateException("Webhook must have exactly one current secret");
        return current.getFirst();
    }

    private ApplicationWebhookResponse response(ApplicationWebhook webhook, long version) {
        return new ApplicationWebhookResponse(webhook.getId(), webhook.getApplicationId(), webhook.getUrl(),
                webhook.getStatus(), version, webhook.getCreatedAt().toInstant(ZoneOffset.UTC),
                webhook.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
