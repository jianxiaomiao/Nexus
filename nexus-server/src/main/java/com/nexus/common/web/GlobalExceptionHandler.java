package com.nexus.common.web;

import com.nexus.application.exception.ApplicationNameAlreadyExistsException;
import com.nexus.application.exception.ApplicationDisabledException;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.exception.InvalidApplicationIdException;
import com.nexus.application.exception.InvalidApplicationUpdateException;
import com.nexus.apikey.exception.ApiKeyNameAlreadyExistsException;
import com.nexus.apikey.exception.ApiKeyNotFoundException;
import com.nexus.apikey.exception.ApiKeyRotationConflictException;
import com.nexus.apikey.exception.InvalidApiKeyDeleteException;
import com.nexus.apikey.exception.InvalidApiKeyRotationException;
import com.nexus.apikey.exception.InvalidApiKeyUpdateException;
import com.nexus.auth.exception.AccountForbiddenException;
import com.nexus.auth.exception.EmailAlreadyRegisteredException;
import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.exception.InvalidCredentialsException;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import com.nexus.auth.exception.ApiKeyForbiddenException;
import com.nexus.openapi.exception.HashAlgorithmRequiredException;
import com.nexus.openapi.exception.HashContentRequiredException;
import com.nexus.openapi.exception.HashInputTooLargeException;
import com.nexus.shortlink.exception.InvalidShortLinkRequestException;
import com.nexus.shortlink.exception.ShortCodeExhaustedException;
import com.nexus.shortlink.exception.ShortLinkExpiredException;
import com.nexus.shortlink.exception.ShortLinkNotFoundException;
import com.nexus.usage.exception.InvalidUsageTimeRangeException;
import com.nexus.usage.exception.InvalidUsagePaginationException;
import com.nexus.webExtract.exception.InvalidWebExtractRequestException;
import com.nexus.webExtract.exception.WebContentUnavailableException;
import com.nexus.webExtract.exception.WebPageFetchException;
import com.nexus.screenshot.exception.ScreenshotException;
import com.nexus.quota.exception.ApplicationQuotaExhaustedException;
import com.nexus.job.dto.ScreenshotJobFailureResponse;
import com.nexus.job.dto.ScreenshotJobNotReadyResponse;
import com.nexus.job.exception.ScreenshotJobCreationException;
import com.nexus.job.exception.ScreenshotJobFailedException;
import com.nexus.job.exception.ScreenshotJobNotFoundException;
import com.nexus.job.exception.ScreenshotJobNotReadyException;
import com.nexus.job.exception.ScreenshotJobQueueFullException;
import com.nexus.job.exception.ScreenshotJobResultExpiredException;
import com.nexus.job.exception.ScreenshotJobSubmissionUnavailableException;
import com.nexus.job.store.ScreenshotResultStoreException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.nexus.common.web.RequestFailureLogContext.Category.CAPACITY_PROTECTION;
import static com.nexus.common.web.RequestFailureLogContext.Category.SYSTEM_FAILURE;

@RestControllerAdvice
public class GlobalExceptionHandler{

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    @ExceptionHandler(ApplicationQuotaExhaustedException.class)
    public ApiResponse<Void> handleApplicationQuotaExhausted(ApplicationQuotaExhaustedException exception,
                                                            HttpServletRequest request) {
        return handled(request, exception, HttpStatus.TOO_MANY_REQUESTS,
                "APPLICATION_QUOTA_EXHAUSTED", exception.getMessage(), null);
    }

    @ExceptionHandler(ScreenshotException.class)
    public ResponseEntity<ApiResponse<Void>> handleScreenshot(ScreenshotException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.kind()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case PAGE_FETCH_FAILED -> HttpStatus.BAD_GATEWAY;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case LIMIT_EXCEEDED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case BUSY, ENGINE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        RequestFailureLogContext.Category category = exception.kind() == ScreenshotException.Kind.BUSY
                ? CAPACITY_PROTECTION
                : defaultCategory(status);
        return ResponseEntity.status(status).body(handled(request, exception, status,
                exception.code(), exception.getMessage(), null, category));
    }

    @ExceptionHandler(ScreenshotJobQueueFullException.class)
    public ResponseEntity<ApiResponse<Void>> handleScreenshotJobQueueFull(
            ScreenshotJobQueueFullException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(handled(request, exception, status,
                        "SCREENSHOT_JOB_QUEUE_FULL", exception.getMessage(), null,
                        CAPACITY_PROTECTION));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(ScreenshotJobNotFoundException.class)
    public ApiResponse<Void> handleScreenshotJobNotFound(
            ScreenshotJobNotFoundException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.NOT_FOUND,
                "SCREENSHOT_JOB_NOT_FOUND", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ScreenshotJobNotReadyException.class)
    public ApiResponse<ScreenshotJobNotReadyResponse> handleScreenshotJobNotReady(
            ScreenshotJobNotReadyException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.CONFLICT,
                "SCREENSHOT_JOB_NOT_READY", exception.getMessage(),
                new ScreenshotJobNotReadyResponse(exception.status()));
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ScreenshotJobFailedException.class)
    public ApiResponse<ScreenshotJobFailureResponse> handleScreenshotJobFailed(
            ScreenshotJobFailedException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.CONFLICT,
                "SCREENSHOT_JOB_FAILED", exception.getMessage(),
                new ScreenshotJobFailureResponse(exception.failureCode()));
    }

    @ResponseStatus(HttpStatus.GONE)
    @ExceptionHandler(ScreenshotJobResultExpiredException.class)
    public ApiResponse<Void> handleScreenshotJobResultExpired(
            ScreenshotJobResultExpiredException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.GONE,
                "SCREENSHOT_JOB_RESULT_EXPIRED", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(ScreenshotJobSubmissionUnavailableException.class)
    public ApiResponse<Void> handleScreenshotJobSubmissionUnavailable(
            ScreenshotJobSubmissionUnavailableException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.SERVICE_UNAVAILABLE,
                "SCREENSHOT_JOB_SUBMISSION_UNAVAILABLE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(ScreenshotJobCreationException.class)
    public ApiResponse<Void> handleScreenshotJobCreation(
            ScreenshotJobCreationException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.INTERNAL_SERVER_ERROR,
                "SCREENSHOT_JOB_CREATE_FAILED", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(ScreenshotResultStoreException.class)
    public ApiResponse<Void> handleScreenshotJobResultUnavailable(
            ScreenshotResultStoreException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.INTERNAL_SERVER_ERROR,
                "SCREENSHOT_JOB_RESULT_UNAVAILABLE", "截图任务结果文件不可用", null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidWebExtractRequestException.class)
    public ApiResponse<Void> handleInvalidWebExtractRequest(InvalidWebExtractRequestException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST, "INVALID_WEB_EXTRACT_REQUEST", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    @ExceptionHandler(WebPageFetchException.class)
    public ApiResponse<Void> handleWebPageFetch(WebPageFetchException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_GATEWAY, "WEB_PAGE_FETCH_FAILED", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    @ExceptionHandler(WebContentUnavailableException.class)
    public ApiResponse<Void> handleWebContentUnavailable(WebContentUnavailableException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.UNPROCESSABLE_ENTITY, "WEB_CONTENT_UNAVAILABLE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidListPageException.class)
    public ApiResponse<Void> handleInvalidListPage(InvalidListPageException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST, "INVALID_LIST_PAGE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidUsagePaginationException.class)
    public ApiResponse<Void> handleInvalidUsagePagination(InvalidUsagePaginationException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST, "INVALID_USAGE_PAGINATION", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidUsageTimeRangeException.class)
    public ApiResponse<Void> handleInvalidUsageTimeRange(InvalidUsageTimeRangeException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST, "INVALID_USAGE_TIME_RANGE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidShortLinkRequestException.class)
    public ApiResponse<Void> handleInvalidShortLinkRequest(InvalidShortLinkRequestException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST, "INVALID_SHORT_LINK_REQUEST", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(ShortLinkNotFoundException.class)
    public ApiResponse<Void> handleShortLinkNotFound(ShortLinkNotFoundException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.NOT_FOUND, "SHORT_LINK_NOT_FOUND", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.GONE)
    @ExceptionHandler(ShortLinkExpiredException.class)
    public ApiResponse<Void> handleShortLinkExpired(ShortLinkExpiredException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.GONE, "SHORT_LINK_EXPIRED", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(ShortCodeExhaustedException.class)
    public ApiResponse<Void> handleShortCodeExhausted(ShortCodeExhaustedException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.SERVICE_UNAVAILABLE, "SHORT_CODE_UNAVAILABLE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadableRequestBody(HttpMessageNotReadableException exception, HttpServletRequest request) {
        // JSON 转换发生在 Controller 与 Bean Validation 之前，不向客户端回显原始输入或底层异常。
        return handled(request, exception, HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", "请求体格式错误或字段值无效", null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HashContentRequiredException.class)
    public ApiResponse<Void> handleHashContentRequired(HashContentRequiredException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST, "HASH_CONTENT_REQUIRED", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HashAlgorithmRequiredException.class)
    public ApiResponse<Void> handleHashAlgorithmRequired(HashAlgorithmRequiredException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST, "HASH_ALGORITHM_REQUIRED", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HashInputTooLargeException.class)
    public ApiResponse<Void> handleHashInputTooLarge(HashInputTooLargeException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST, "HASH_INPUT_TOO_LARGE", exception.getMessage(), null);
    }

    @ExceptionHandler(InvalidApiKeyCredentialException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidApiKeyCredential(InvalidApiKeyCredentialException exception, HttpServletRequest request) {
        logHandled(request, exception, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY_CREDENTIAL",
                RequestFailureLogContext.Category.BUSINESS_REJECTION);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"nexus-openapi\"")
                .body(new ApiResponse<>("INVALID_API_KEY_CREDENTIAL", exception.getMessage(), null));
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(ApiKeyForbiddenException.class)
    public ApiResponse<Void> handleApiKeyForbidden(ApiKeyForbiddenException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.FORBIDDEN, "API_KEY_FORBIDDEN", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ApiResponse<Void> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.CONFLICT,
                "AUTH_EMAIL_ALREADY_REGISTERED", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Map<String, String>> handleValidationException(
            MethodArgumentNotValidException exception, HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError fieldError
                : exception.getBindingResult().getFieldErrors()) {

            fieldErrors.putIfAbsent(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            );
        }

        return handled(request, exception, HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR", "请求参数校验失败", fieldErrors);
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(InvalidCredentialsException.class)
    public ApiResponse<Void> handleInvalidCredentialsException(InvalidCredentialsException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.UNAUTHORIZED,
                "AUTH_INVALID_CREDENTIALS", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(AccountForbiddenException.class)
    public ApiResponse<Void> handleAccountForbiddenException(AccountForbiddenException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.FORBIDDEN,
                "AUTH_ACCOUNT_FORBIDDEN", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ApplicationNameAlreadyExistsException.class)
    public ApiResponse<Void> handleApplicationNameAlreadyExistsException(ApplicationNameAlreadyExistsException exception, HttpServletRequest request){
        return handled(request, exception, HttpStatus.CONFLICT,
                "APPLICATION_NAME_ALREADY_EXISTS", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(InvalidAccessTokenException.class)
    public ApiResponse<Void> handleInvalidAccessTokenException(InvalidAccessTokenException exception, HttpServletRequest request){
        return handled(request, exception, HttpStatus.UNAUTHORIZED,
                "INVALID_ACCESS_TOKEN", exception.getMessage(), null);
    }
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(ApplicationNotFoundException.class)
    public ApiResponse<Void> handleApplicationNotFoundException(ApplicationNotFoundException exception, HttpServletRequest request){
        return handled(request, exception, HttpStatus.NOT_FOUND,
                "APPLICATION_NOT_FOUND", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ApplicationDisabledException.class)
    public ApiResponse<Void> handleApplicationDisabledException(ApplicationDisabledException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.CONFLICT,
                "APPLICATION_DISABLED", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ApiKeyNameAlreadyExistsException.class)
    public ApiResponse<Void> handleApiKeyNameAlreadyExistsException(ApiKeyNameAlreadyExistsException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.CONFLICT,
                "API_KEY_NAME_ALREADY_EXISTS", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ApiKeyRotationConflictException.class)
    public ApiResponse<Void> handleApiKeyRotationConflict(ApiKeyRotationConflictException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.CONFLICT,
                "API_KEY_ROTATION_CONFLICT", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidApplicationUpdateException.class)
    public ApiResponse<Void> handleInvalidApplicationUpdateException(InvalidApplicationUpdateException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST,
                "INVALID_APPLICATION_UPDATE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidApiKeyDeleteException.class)
    public ApiResponse<Void> handleInvalidApiKeyDeleteException(InvalidApiKeyDeleteException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST, "INVALID_API_KEY_DELETE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidApiKeyUpdateException.class)
    public ApiResponse<Void> handleInvalidApiKeyUpdateException(InvalidApiKeyUpdateException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST, "INVALID_API_KEY_UPDATE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidApiKeyRotationException.class)
    public ApiResponse<Void> handleInvalidApiKeyRotation(InvalidApiKeyRotationException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST,
                "INVALID_API_KEY_ROTATION", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(ApiKeyNotFoundException.class)
    public ApiResponse<Void> handleApiKeyNotFoundException(ApiKeyNotFoundException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidApplicationIdException.class)
    public ApiResponse<Void> handleInvalidApplicationIdException(InvalidApplicationIdException exception, HttpServletRequest request) {
        return handled(request, exception, HttpStatus.BAD_REQUEST,
                "INVALID_APPLICATION_ID", exception.getMessage(), null);
    }

    private <T> ApiResponse<T> handled(HttpServletRequest request, Exception exception,
                                       HttpStatus status, String code, String message, T data) {
        return handled(request, exception, status, code, message, data,
                defaultCategory(status));
    }

    private <T> ApiResponse<T> handled(HttpServletRequest request, Exception exception,
                                       HttpStatus status, String code, String message, T data,
                                       RequestFailureLogContext.Category category) {
        logHandled(request, exception, status, code, category);
        return new ApiResponse<>(code, message, data);
    }

    private void logHandled(HttpServletRequest request, Exception exception, HttpStatus status,
                            String code, RequestFailureLogContext.Category category) {
        request.setAttribute(RequestFailureLoggingFilter.HANDLED_EXCEPTION_LOGGED, Boolean.TRUE);
        RequestFailureLogContext.mark(request, category, code);
        Object diagnostic = exception instanceof ScreenshotException screenshot ? screenshot.diagnostic() : null;
        String route = RequestFailureLogContext.route(request);
        if (category == CAPACITY_PROTECTION) {
            log.warn("请求受容量保护 method={}, route={}, status={}, code={}, category={}, exception={}, diagnostic={}",
                    request.getMethod(), route, status.value(), code, category.value(),
                    exception.getClass().getSimpleName(), diagnostic);
        } else if (category == SYSTEM_FAILURE) {
            log.error("请求处理失败 method={}, route={}, status={}, code={}, category={}, exception={}, diagnostic={}",
                    request.getMethod(), route, status.value(), code, category.value(),
                    exception.getClass().getSimpleName(), diagnostic, exception);
        } else {
            // 不记录异常消息，避免将用户输入、SQL 或凭据写入日志。
            log.info("请求被拒绝 method={}, route={}, status={}, code={}, category={}, exception={}, diagnostic={}",
                    request.getMethod(), route, status.value(), code, category.value(),
                    exception.getClass().getSimpleName(), diagnostic);
        }
    }

    private static RequestFailureLogContext.Category defaultCategory(HttpStatus status) {
        return status.is5xxServerError()
                ? SYSTEM_FAILURE
                : RequestFailureLogContext.Category.BUSINESS_REJECTION;
    }
}
