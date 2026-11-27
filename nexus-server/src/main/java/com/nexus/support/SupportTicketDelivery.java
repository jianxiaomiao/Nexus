package com.nexus.support;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
class SupportTicketDelivery {
    private static final Logger log = LoggerFactory.getLogger(SupportTicketDelivery.class);
    private static final int MAX_ATTEMPTS = 12;
    private static final int MAX_RECEIPT_BYTES = 4096;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SupportDestination destination;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry metrics;

    SupportTicketDelivery(JdbcTemplate jdbc, PlatformTransactionManager transactions, SupportDestination destination,
                          ObjectMapper json, Clock clock, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.destination = destination;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${nexus.support.flowops.poll-ms:1000}", scheduler = "supportTicketScheduler")
    public void dispatch() {
        if (!destination.enabled()) return;
        Delivery item = claim();
        if (item == null) return;
        Outcome outcome = send(item);
        int changed;
        if (outcome.ack != null) {
            changed = jdbc.update("""
                    UPDATE support_ticket_outbox SET status = 'DELIVERED', delivered_at = UTC_TIMESTAMP(3),
                        flowops_ticket_id = ?, flowops_ticket_no = ?, claim_token = NULL, lease_expires_at = NULL, last_failure = NULL
                    WHERE event_id = ? AND claim_token = ? AND status = 'PROCESSING' AND lease_expires_at > UTC_TIMESTAMP(3)
                    """, outcome.ack.flowOpsTicketId, outcome.ack.ticketNo, item.eventId, item.token);
        } else {
            boolean retry = outcome.retry && item.attempt <= MAX_ATTEMPTS - 1;
            changed = jdbc.update("""
                    UPDATE support_ticket_outbox SET status = ?, last_failure = ?,
                        next_attempt_at = TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(3)),
                        claim_token = NULL, lease_expires_at = NULL
                    WHERE event_id = ? AND claim_token = ? AND status = 'PROCESSING' AND lease_expires_at > UTC_TIMESTAMP(3)
                    """, retry ? "PENDING" : "BLOCKED", outcome.failure,
                    retry ? retryDelay(item.attempt, outcome.retryAfter) : 0, item.eventId, item.token);
            if (changed == 1 && !retry) {
                log.warn("Support ticket delivery needs review, eventId={} integrationId={} reason={}",
                        item.eventId, destination.id, outcome.failure);
            }
        }
        if (changed == 1) {
            metrics.counter("nexus.support.delivery", "outcome", outcome.ack != null ? "confirmed" : outcome.failure).increment();
        }
    }

    Delivery claim() {
        return transaction.execute(status -> {
            jdbc.update("""
                    UPDATE support_ticket_outbox SET status = 'BLOCKED', last_failure = 'LEASE_EXHAUSTED',
                        claim_token = NULL, lease_expires_at = NULL
                    WHERE integration_id = ? AND status = 'PROCESSING' AND lease_expires_at <= UTC_TIMESTAMP(3)
                        AND attempt_count >= ?
                    """, destination.id, MAX_ATTEMPTS);
            var expired = jdbc.query("""
                    SELECT event_id, ticket_id, payload, attempt_count FROM support_ticket_outbox
                    WHERE integration_id = ? AND status = 'PROCESSING' AND lease_expires_at <= UTC_TIMESTAMP(3)
                    ORDER BY lease_expires_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> delivery(rs), destination.id);
            var ready = expired.isEmpty() ? jdbc.query("""
                    SELECT event_id, ticket_id, payload, attempt_count FROM support_ticket_outbox
                    WHERE integration_id = ? AND status = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(3)
                    ORDER BY next_attempt_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> delivery(rs), destination.id) : expired;
            if (ready.isEmpty()) return null;
            Delivery item = ready.getFirst();
            jdbc.update("""
                    UPDATE support_ticket_outbox SET status = 'PROCESSING', attempt_count = ?,
                        claim_token = ?, lease_expires_at = TIMESTAMPADD(SECOND, 60, UTC_TIMESTAMP(3))
                    WHERE event_id = ?
                    """, item.attempt, item.token, item.eventId);
            return item;
        });
    }

    private static Delivery delivery(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Delivery(rs.getString("event_id"), rs.getString("ticket_id"), rs.getString("payload"),
                rs.getInt("attempt_count") + 1, UUID.randomUUID().toString());
    }

    Outcome send(Delivery item) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) destination.endpoint.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", destination.authorization());
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Trace-Id", item.eventId);
            byte[] payload = item.payload.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            try (var body = connection.getOutputStream()) {
                body.write(payload);
            }
            int status = connection.getResponseCode();
            if (status == 200 || status == 201) {
                try (var body = connection.getInputStream()) {
                    return validateReceipt(item, body.readNBytes(MAX_RECEIPT_BYTES + 1));
                }
            }
            if (status == 408 || status == 429 || status >= 500) {
                return new Outcome(null, "HTTP_" + status, true, retryAfter(connection.getHeaderField("Retry-After")));
            }
            return new Outcome(null, "HTTP_" + status, false, 0);
        } catch (IOException error) {
            return new Outcome(null, "NETWORK_ERROR", true, 0);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    Outcome validateReceipt(Delivery item, byte[] body) {
        if (body.length <= MAX_RECEIPT_BYTES) {
            try {
                Ack ack = json.readValue(body, Ack.class);
                if (ack != null && item.eventId.equals(ack.eventId) && item.ticketId.equals(ack.externalTicketId)
                        && ack.flowOpsTicketId != null && ack.flowOpsTicketId.matches("[1-9][0-9]{0,19}")
                        && ack.ticketNo != null && ack.ticketNo.matches("[A-Za-z0-9_-]{1,40}")) {
                    return new Outcome(ack, null, false, 0);
                }
            } catch (tools.jackson.core.JacksonException invalid) {
                return new Outcome(null, "INVALID_ACK", true, 0);
            }
        }
        return new Outcome(null, "INVALID_ACK", true, 0);
    }

    long retryAfter(String value) {
        if (value == null) return 0;
        try {
            return Math.clamp(Long.parseLong(value), 0, 86400);
        } catch (NumberFormatException ignored) {
            try {
                var time = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.clamp(Duration.between(clock.instant(), time).toSeconds(), 0, 86400);
            } catch (java.time.format.DateTimeParseException invalid) {
                return 0;
            }
        }
    }

    private static long retryDelay(int attempt, long retryAfter) {
        long base = 1L << Math.min(attempt, 10);
        return Math.max(retryAfter, base + ThreadLocalRandom.current().nextLong(base));
    }

    record Delivery(String eventId, String ticketId, String payload, int attempt, String token) {}
    record Ack(String eventId, String externalTicketId, String flowOpsTicketId, String ticketNo) {}
    record Outcome(Ack ack, String failure, boolean retry, long retryAfter) {}
}
