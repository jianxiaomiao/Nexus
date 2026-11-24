package com.nexus.support;

import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.ApiResponse;
import com.nexus.common.web.ListPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.UUID;

@RestController
@RequestMapping("/api/support/tickets")
class SupportTicketController {
    private final SupportTicketService tickets;
    private final BearerUserIdResolver identities;

    SupportTicketController(SupportTicketService tickets, BearerUserIdResolver identities) {
        this.tickets = tickets;
        this.identities = identities;
    }

    @PostMapping
    ResponseEntity<ApiResponse<SupportTicketService.Ticket>> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @Valid @RequestBody Submission input, HttpServletRequest request) {
        long owner = identities.resolve(auth);
        var headers = Collections.list(request.getHeaders("Idempotency-Key"));
        if (headers.size() != 1 || !headers.getFirst().matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new SupportTicketException(HttpStatus.BAD_REQUEST, "SUPPORT_SUBMISSION_KEY_REQUIRED", "需要工单提交标识");
        }
        var result = tickets.submit(owner, UUID.fromString(headers.getFirst()), input);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(success(result.ticket()));
    }

    @GetMapping
    ApiResponse<ListPage<SupportTicketService.Ticket>> list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "10") long size) {
        return success(tickets.list(identities.resolve(auth), current, size));
    }

    @GetMapping("/{id}")
    ApiResponse<SupportTicketService.Ticket> get(
            @RequestHeader(value = "Authorization", required = false) String auth, @PathVariable UUID id) {
        return success(tickets.get(identities.resolve(auth), id));
    }

    @ExceptionHandler(SupportTicketException.class)
    ResponseEntity<ApiResponse<Void>> supportFailure(SupportTicketException error) {
        return ResponseEntity.status(error.status).body(new ApiResponse<>(error.code, error.getMessage(), null));
    }

    private static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", null, data);
    }

    record Submission(@NotBlank @Size(max = 200) String title,
                      @NotBlank @Size(max = 8000) String description, @Positive Long applicationId) {}
}

final class SupportTicketException extends RuntimeException {
    final HttpStatus status;
    final String code;

    SupportTicketException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
