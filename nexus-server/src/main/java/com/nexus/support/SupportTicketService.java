package com.nexus.support;

import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.common.web.ListPage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
class SupportTicketService {
    private static final String TICKETS = """
            SELECT t.id, t.title, t.description, t.application_id, t.created_at, t.submission_digest,
                o.status, o.flowops_ticket_no
            FROM support_ticket t JOIN support_ticket_outbox o ON o.ticket_id = t.id
            WHERE t.owner_user_id = ?
            """;
    private static final RowMapper<Ticket> MAP_TICKET = (rs, row) -> ticket(rs);

    private final JdbcTemplate jdbc;
    private final ApplicationMapper applications;
    private final SupportDestination destination;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transaction;

    SupportTicketService(JdbcTemplate jdbc, ApplicationMapper applications, SupportDestination destination,
                         ObjectMapper json, Clock clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.applications = applications;
        this.destination = destination;
        this.json = json;
        this.clock = clock;
        transaction = new TransactionTemplate(transactions);
    }

    SubmissionResult submit(long owner, UUID key, SupportTicketController.Submission input) {
        byte[] digest = digest(input);
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                Ticket prior = findSubmission(owner, key, digest);
                if (prior != null) return new SubmissionResult(false, prior);
                if (!destination.enabled()) {
                    throw new SupportTicketException(HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_UNAVAILABLE", "工单接入暂未启用");
                }
                if (input.applicationId() != null && applications.selectOwnedIdForShare(owner, input.applicationId()) == null) {
                    throw notFound();
                }
                UUID ticketId = UUID.randomUUID();
                UUID eventId = UUID.randomUUID();
                Instant submittedAt = clock.instant();
                String payload = json.writeValueAsString(new Event(1, eventId, ticketId, input.title(),
                        input.description(), submittedAt.toString(), input.applicationId()));
                jdbc.update("""
                        INSERT INTO support_ticket
                            (id, owner_user_id, submission_key, submission_digest, application_id, title, description, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, ticketId.toString(), owner, key.toString(), digest, input.applicationId(),
                        input.title(), input.description(), LocalDateTime.ofInstant(submittedAt, ZoneOffset.UTC));
                jdbc.update("""
                        INSERT INTO support_ticket_outbox (event_id, ticket_id, integration_id, payload)
                        VALUES (?, ?, ?, ?)
                        """, eventId.toString(), ticketId.toString(), destination.id, payload);
                return new SubmissionResult(true, get(owner, ticketId));
            }));
        } catch (DuplicateKeyException conflict) {
            Ticket prior = findSubmission(owner, key, digest);
            if (prior != null) return new SubmissionResult(false, prior);
            throw conflict;
        }
    }

    Ticket get(long owner, UUID id) {
        return jdbc.query(TICKETS + " AND t.id = ?", MAP_TICKET, owner, id.toString())
                .stream().findFirst().orElseThrow(SupportTicketService::notFound);
    }

    ListPage<Ticket> list(long owner, long current, long size) {
        if (current < 1 || current > Integer.MAX_VALUE || size < 1 || size > 50) {
            throw new SupportTicketException(HttpStatus.BAD_REQUEST, "SUPPORT_INVALID_PAGE", "工单分页不正确");
        }
        long count = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_ticket WHERE owner_user_id = ?", Long.class, owner));
        List<Ticket> rows = jdbc.query(TICKETS + " ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?",
                MAP_TICKET, owner, size, (current - 1) * size);
        return new ListPage<>(count, current, size, rows);
    }

    private Ticket findSubmission(long owner, UUID key, byte[] digest) {
        return jdbc.query(TICKETS + " AND t.submission_key = ?", rs -> {
            if (!rs.next()) return null;
            if (!MessageDigest.isEqual(digest, rs.getBytes("submission_digest"))) {
                throw new SupportTicketException(HttpStatus.CONFLICT, "SUPPORT_SUBMISSION_CONFLICT", "提交标识已用于另一份内容");
            }
            return ticket(rs);
        }, owner, key.toString());
    }

    private byte[] digest(SupportTicketController.Submission input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Ticket ticket(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        String displayStatus = switch (status) {
            case "DELIVERED", "BLOCKED" -> status;
            default -> "PENDING";
        };
        return new Ticket(rs.getString("id"), rs.getString("title"), rs.getString("description"),
                rs.getObject("application_id", Long.class), rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                displayStatus, rs.getString("flowops_ticket_no"));
    }

    private static SupportTicketException notFound() {
        return new SupportTicketException(HttpStatus.NOT_FOUND, "SUPPORT_NOT_FOUND", "工单或应用不存在");
    }

    record Ticket(String id, String title, String description, Long applicationId, Instant createdAt,
                  String deliveryStatus, String flowOpsTicketNo) {}
    record SubmissionResult(boolean created, Ticket ticket) {}
    record Event(int schemaVersion, UUID eventId, UUID externalTicketId, String title, String description,
                 String submittedAt, Long applicationId) {}
}
