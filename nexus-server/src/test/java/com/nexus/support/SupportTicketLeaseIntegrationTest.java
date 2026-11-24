package com.nexus.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@EnabledIfEnvironmentVariable(named = "NEXUS_SUPPORT_LEASE_ACCEPTANCE", matches = "true")
class SupportTicketLeaseIntegrationTest {
    @Test
    void expiredLeaseIsReclaimedAndOldReceiptCannotWriteOverTheNewClaim() throws Exception {
        var dataSource = acceptanceDataSource();
        var jdbc = new JdbcTemplate(dataSource);
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT DATABASE() = 'nexus_test'
                    AND SUBSTRING_INDEX(CURRENT_USER(), '@', 1) = 'nexus_test_user'
                    AND VERSION() REGEXP '^8[.]4[.][0-9]+($|[-+])'
                """, Boolean.class)), "Expected dedicated Nexus MySQL 8.4 test database and account");
        Flyway.configure().dataSource(dataSource).load().migrate();

        String ticketId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        var destination = new SupportDestination("lease-" + eventId,
                URI.create("http://127.0.0.1/integration/v1/tickets"), "unused");
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var firstReply = new CountDownLatch(1);
        var secondReply = new CountDownLatch(1);
        var firstClaim = new AtomicReference<SupportTicketDelivery.Delivery>();
        var secondClaim = new AtomicReference<SupportTicketDelivery.Delivery>();
        var transactions = new DataSourceTransactionManager(dataSource);
        var firstMetrics = new SimpleMeterRegistry();
        var secondMetrics = new SimpleMeterRegistry();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = worker(jdbc, transactions, destination, firstMetrics, firstClaim, firstEntered, firstReply, "OPS-OLD");
            var second = worker(jdbc, transactions, destination, secondMetrics, secondClaim, secondEntered, secondReply, "OPS-NEW");
            try {
                jdbc.update("""
                        INSERT INTO support_ticket (id, owner_user_id, submission_key, submission_digest, title, description)
                        VALUES (?, 0, ?, ?, 'Lease acceptance', 'Scoped test fixture')
                        """, ticketId, UUID.randomUUID().toString(), new byte[32]);
                jdbc.update("""
                        INSERT INTO support_ticket_outbox (event_id, ticket_id, integration_id, payload)
                        VALUES (?, ?, ?, '{}')
                        """, eventId, ticketId, destination.id);

                var oldDispatch = pool.submit(first::dispatch);
                assertTrue(firstEntered.await(10, SECONDS));
                assertEquals(eventId, firstClaim.get().eventId());
                assertEquals(1, firstClaim.get().attempt());
                assertEquals(1, jdbc.update("""
                        UPDATE support_ticket_outbox SET lease_expires_at = UTC_TIMESTAMP(3) - INTERVAL 1 SECOND
                        WHERE event_id = ? AND status = 'PROCESSING' AND claim_token = ?
                        """, eventId, firstClaim.get().token()));

                var newDispatch = pool.submit(second::dispatch);
                assertTrue(secondEntered.await(10, SECONDS));
                assertEquals(eventId, secondClaim.get().eventId());
                assertEquals(2, secondClaim.get().attempt());
                assertNotEquals(firstClaim.get().token(), secondClaim.get().token());

                firstReply.countDown();
                oldDispatch.get(10, SECONDS);
                assertEquals(new Row("PROCESSING", 2, secondClaim.get().token(), null), row(jdbc, eventId));
                assertNull(firstMetrics.find("nexus.support.delivery").counter());

                secondReply.countDown();
                newDispatch.get(10, SECONDS);
                assertEquals(new Row("DELIVERED", 2, null, "OPS-NEW"), row(jdbc, eventId));
                assertEquals(1.0, secondMetrics.get("nexus.support.delivery").tag("outcome", "confirmed").counter().count());
            } finally {
                firstReply.countDown();
                secondReply.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(25, SECONDS));
                jdbc.update("DELETE FROM support_ticket_outbox WHERE event_id = ?", eventId);
                jdbc.update("DELETE FROM support_ticket WHERE id = ?", ticketId);
            }
        }
    }

    private static DriverManagerDataSource acceptanceDataSource() {
        String url = Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_URL"), "Missing test JDBC URL");
        assertTrue(url.matches("jdbc:mysql://(?:127[.]0[.]0[.]1|localhost|\\[::1\\])(?::[0-9]+)?/nexus_test(?:\\?.*)?"),
                "Lease acceptance requires loopback MySQL and schema nexus_test");
        return new DriverManagerDataSource(url,
                Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_USERNAME"), "Missing test DB user"),
                Objects.requireNonNull(System.getenv("SPRING_DATASOURCE_PASSWORD"), "Missing test DB password"));
    }

    private static SupportTicketDelivery worker(JdbcTemplate jdbc, DataSourceTransactionManager transactions,
            SupportDestination destination, SimpleMeterRegistry metrics,
            AtomicReference<SupportTicketDelivery.Delivery> claimed, CountDownLatch entered, CountDownLatch reply,
            String ticketNo) {
        var worker = spy(new SupportTicketDelivery(jdbc, transactions, destination, new JsonMapper(),
                Clock.systemUTC(), metrics));
        doAnswer(call -> {
            SupportTicketDelivery.Delivery item = call.getArgument(0);
            claimed.set(item);
            entered.countDown();
            assertTrue(reply.await(25, SECONDS));
            var ack = new SupportTicketDelivery.Ack(item.eventId(), item.ticketId(), "12", ticketNo);
            return new SupportTicketDelivery.Outcome(ack, null, false, 0);
        }).when(worker).send(any());
        return worker;
    }

    private static Row row(JdbcTemplate jdbc, String eventId) {
        return jdbc.queryForObject("""
                SELECT status, attempt_count, claim_token, flowops_ticket_no
                FROM support_ticket_outbox WHERE event_id = ?
                """, (rs, row) -> new Row(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4)), eventId);
    }

    private record Row(String status, int attempts, String token, String ticketNo) {}
}
