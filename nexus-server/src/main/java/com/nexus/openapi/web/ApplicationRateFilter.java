package com.nexus.openapi.web;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.common.web.ApiResponse;
import com.nexus.openapi.ratelimit.ApplicationRateLimiter;
import com.nexus.openapi.ratelimit.InvalidRateLimitApplicationIdException;
import com.nexus.openapi.ratelimit.QueueDecision;
import com.nexus.openapi.ratelimit.RateLimitAdmission;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@AllArgsConstructor
public class ApplicationRateFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ApplicationRateFilter.class);

    private final ApplicationRateLimiter rateLimiter;
    private final MachineIdentityResolver machineIdentityResolver;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Object value = request.getAttribute(QUEUE_DECISION_ATTRIBUTE);

        if (value instanceof QueueDecision decision) {
            request.removeAttribute(QUEUE_DECISION_ATTRIBUTE);
            handleQueueDecision(decision, request, response, filterChain);
            return;
        }

        ApiKeyIdentity identity = machineIdentityResolver.require(request);

        RateLimitAdmission admission;
        try {
            admission = rateLimiter.acquire(identity.applicationId());
        } catch (InvalidRateLimitApplicationIdException exception) {
            log.error("限流身份无效 method={}, path={}",
                    request.getMethod(), request.getRequestURI(), exception);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "RATE_LIMIT_IDENTITY_INVALID", exception.getMessage());
            return;
        }

        if (admission instanceof RateLimitAdmission.Allowed) {
            filterChain.doFilter(request, response);
            return;
        }

        if (admission instanceof RateLimitAdmission.Rejected rejected) {
            handleRejected(rejected.reason(), response);
            return;
        }

        if (admission instanceof RateLimitAdmission.Queued queued) {
            waitAsynchronously(request, queued);
            return;
        }

    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                objectMapper.writeValueAsString(new ApiResponse<>(code, message, null)));
    }

    private void handleQueueDecision(
            QueueDecision decision,
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        switch (decision) {
            case ALLOWED -> filterChain.doFilter(request, response);
            case TIMED_OUT -> {
                response.setHeader(HttpHeaders.RETRY_AFTER, "1");
                writeError(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                        "APPLICATION_RATE_WAIT_TIMEOUT", "请求等待限流许可超时");
            }
            case LIMITER_UNAVAILABLE -> writeError(
                    response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "RATE_LIMITER_UNAVAILABLE", "限流服务暂时不可用");
            case CANCELLED -> writeError(
                    response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "RATE_LIMIT_WAIT_CANCELLED", "限流等待已取消");
        }
    }

    private void handleRejected(
            RateLimitAdmission.RejectReason reason,
            HttpServletResponse response) throws IOException {

        switch (reason) {
            case APPLICATION_QUEUE_FULL -> {
                response.setHeader(HttpHeaders.RETRY_AFTER, "1");
                writeError(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                        "APPLICATION_RATE_QUEUE_FULL", "Application 请求等待队列已满");
            }
            case GLOBAL_QUEUE_FULL -> writeError(
                    response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "RATE_LIMIT_GLOBAL_QUEUE_FULL", "限流服务当前等待请求过多");
            case STATE_CAPACITY_REACHED -> writeError(
                    response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "RATE_LIMIT_STATE_CAPACITY_REACHED", "限流服务状态容量已满");
            case LIMITER_UNAVAILABLE -> writeError(
                    response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "RATE_LIMITER_UNAVAILABLE", "限流服务暂时不可用");
        }
    }

    private static final String QUEUE_DECISION_ATTRIBUTE =
            ApplicationRateFilter.class.getName() + ".queueDecision";

    private void waitAsynchronously(
            HttpServletRequest request,
            RateLimitAdmission.Queued queued) {

        AsyncContext asyncContext = request.startAsync();

        asyncContext.addListener(new AsyncListener() {
            @Override
            public void onTimeout(AsyncEvent event) {
                queued.handle().cancel();
            }

            @Override
            public void onError(AsyncEvent event) {
                queued.handle().cancel();
            }

            @Override
            public void onComplete(AsyncEvent event) {
                queued.handle().cancel();
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
            }
        });

        // 限流器最长等待1秒，这里留出调度误差。
        asyncContext.setTimeout(2_000L);

        queued.handle().completion().whenComplete(
                (decision, failure) -> {
                    if (failure != null) {
                        log.error("等待限流结果时发生异常 method={}, path={}",
                                request.getMethod(), request.getRequestURI(), failure);
                    }

                    QueueDecision finalDecision =
                            failure == null
                                    ? decision
                                    : QueueDecision.LIMITER_UNAVAILABLE;

                    asyncContext.getRequest().setAttribute(
                            QUEUE_DECISION_ATTRIBUTE,
                            finalDecision);

                    try {
                        asyncContext.dispatch();
                    } catch (IllegalStateException exception) {
                        // 请求可能已经超时、断开或结束。
                        queued.handle().cancel();
                    }
                });
    }
}
