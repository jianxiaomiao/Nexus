package com.nexus.webhook.dispatch;

import com.nexus.webhook.crypto.EncryptedSecret;
import com.nexus.webhook.crypto.WebhookSecretCrypto;
import com.nexus.webhook.crypto.WebhookSignatureService;
import com.nexus.webhook.entity.ApplicationWebhook;
import com.nexus.webhook.entity.WebhookOutbox;
import com.nexus.webhook.entity.WebhookSecret;
import com.nexus.webhook.mapper.ApplicationWebhookMapper;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import com.nexus.webhook.mapper.WebhookSecretMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhookWorkerImplTests {
    @ParameterizedTest
    @ValueSource(strings = {"afterAdmission", "inFlight", "duringDns"})
    void cancellationStopsDeliveryAndFinalWrite(String moment) throws Exception {
        var leases = mock(WebhookLeaseManager.class);
        var session = mock(WebhookLeaseManager.Session.class);
        var preparer = mock(WebhookAttemptPreparer.class);
        var outbox = mock(WebhookOutboxMapper.class);
        var configs = mock(ApplicationWebhookMapper.class);
        var secrets = mock(WebhookSecretMapper.class);
        var http = mock(WebhookHttpClient.class);
        var post = mock(WebhookHttpClient.PreparedPost.class);
        var crypto = mock(WebhookSecretCrypto.class);
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);
        var handler = new WebhookResponseHandler(new WebhookRetryPolicy(5, java.time.Duration.ofSeconds(1), java.time.Duration.ofMinutes(5)), clock);
        var worker = new WebhookWorkerImpl(leases, preparer, outbox, configs, secrets, http, crypto,
                new WebhookSignatureService(), JsonMapper.builder().build(), handler, new WebhookExceptionClassifier(), clock);
        WebhookOutbox event = new WebhookOutbox(); event.setId(1L); event.setEventId("event-1"); event.setApplicationId(2L);
        event.setAttemptCount(0L); event.setSecretRefreshAttempted(false);
        var claim = new ClaimedWebhookEvent(event, "claim", LocalDateTime.now(), System.nanoTime(), 60_000_000_000L);
        ApplicationWebhook config = new ApplicationWebhook(); config.setId(3L); config.setApplicationId(2L); config.setStatus(0); config.setUrl("https://example.test/");
        WebhookSecret secret = new WebhookSecret(); secret.setId(4L); secret.setWebhookId(3L); secret.setSecretCiphertext(new byte[16]);
        secret.setSecretNonce(new byte[12]); secret.setEncryptionAlgorithm("AES-256-GCM"); secret.setEncryptionKeyVersion("v1");
        AtomicBoolean usable = new AtomicBoolean(true);
        when(leases.start(claim)).thenReturn(session);
        when(session.usable()).thenAnswer(inv -> usable.get());
        when(session.status()).thenAnswer(inv -> usable.get() ? LeaseStatus.ACTIVE : LeaseStatus.LEASE_UNCERTAIN);
        var cancellation = new CancellationToken();
        when(session.cancellation()).thenReturn(cancellation);
        when(outbox.selectClaimed(1L, "claim")).thenReturn(event);
        when(configs.selectByApplicationId(2L)).thenReturn(config);
        when(secrets.selectCurrentSecretsByWebhookId(eq(3L), any())).thenReturn(List.of(secret));
        when(crypto.decrypt(any(EncryptedSecret.class))).thenReturn(new byte[32]);
        when(http.preparePost(eq(config.getUrl()), any(), anyLong(), anyString())).thenAnswer(inv -> {
            if (moment.equals("duringDns")) { usable.set(false); cancellation.cancel(); }
            return post;
        });
        when(post.send()).thenAnswer(inv -> {
            usable.set(false);
            cancellation.cancel();
            throw new java.io.IOException("connection cancelled");
        });
        doAnswer(inv -> {
            if (moment.equals("afterAdmission")) { usable.set(false); cancellation.cancel(); }
            @SuppressWarnings("unchecked") var operation = (WebhookAttemptPreparer.HttpOperation<WebhookHttpResponse>) inv.getArgument(6);
            var committed = new WebhookAttemptPreparer.Snapshot(1, false);
            try { operation.send(committed); fail("expected cancellation"); }
            catch (CancellationException | java.io.IOException e) { throw new WebhookSendException(committed, e); }
            return Optional.empty();
        }).when(preparer).prepareAndSend(eq(1L), eq("claim"), any(), eq(WebhookAttemptPreparer.AttemptType.INITIAL),
                eq(config.getUrl()), eq(4L), any());
        worker.process(claim);
        if (moment.equals("inFlight")) {
            verify(post).send();
            verify(post).cancel();
        } else {
            verify(post, never()).send();
        }
        if (moment.equals("duringDns")) verifyNoInteractions(preparer);
        verify(session, never()).complete(any());
        verify(session).close();
        worker.close();
    }
}
