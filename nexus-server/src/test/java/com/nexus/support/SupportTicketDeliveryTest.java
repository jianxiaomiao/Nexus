package com.nexus.support;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SupportTicketDeliveryTest {
    private final SupportTicketDelivery delivery = new SupportTicketDelivery(mock(JdbcTemplate.class),
            mock(PlatformTransactionManager.class), new SupportDestination("", null, ""), new JsonMapper(),
            Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC), new SimpleMeterRegistry());
    private final SupportTicketDelivery.Delivery item =
            new SupportTicketDelivery.Delivery("event-1", "ticket-1", "{}", 1, "lease-1");

    @Test
    void matchesBothIdsBeforeAcceptingReceipt() {
        var outcome = receipt("event-1", "ticket-1", "13", "OPS-20261124-0001");
        assertEquals("13", outcome.ack().flowOpsTicketId());
        assertFalse(outcome.retry());

        assertTrue(receipt("event-2", "ticket-1", "13", "OPS-20261124-0001").retry());
        assertTrue(receipt("event-1", "ticket-2", "13", "OPS-20261124-0001").retry());
        assertNull(receipt("event-1", "ticket-1", "0", "OPS-20261124-0001").ack());
    }

    @Test
    void malformedEmptyOrOversizedReceiptLeavesDeliveryUnconfirmed() {
        for (byte[] bytes : new byte[][]{"broken".getBytes(StandardCharsets.UTF_8), new byte[0], new byte[4097]}) {
            var outcome = delivery.validateReceipt(item, bytes);
            assertNull(outcome.ack());
            assertEquals("INVALID_ACK", outcome.failure());
            assertTrue(outcome.retry());
        }
    }

    @Test
    void understandsSecondsAndHttpDates() {
        assertEquals(17, delivery.retryAfter("17"));
        assertEquals(35, delivery.retryAfter("Tue, 24 Nov 2026 00:00:35 GMT"));
        assertEquals(0, delivery.retryAfter("-3"));
        assertEquals(0, delivery.retryAfter("invalid"));
    }

    private SupportTicketDelivery.Outcome receipt(String eventId, String ticketId, String opsId, String number) {
        return delivery.validateReceipt(item, """
                {"eventId":"%s","externalTicketId":"%s","flowOpsTicketId":"%s","ticketNo":"%s"}
                """.formatted(eventId, ticketId, opsId, number).getBytes(StandardCharsets.UTF_8));
    }
}
