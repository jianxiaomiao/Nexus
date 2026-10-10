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

@RestControllerAdvice
public class GlobalExceptionHandler{

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
        logHandled(request, exception, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY_CREDENTIAL");
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
        logHandled(request, exception, status, code);
        return new ApiResponse<>(code, message, data);
    }

    private void logHandled(HttpServletRequest request, Exception exception, HttpStatus status, String code) {
        request.setAttribute(RequestFailureLoggingFilter.HANDLED_EXCEPTION_LOGGED, Boolean.TRUE);
        if (status.is5xxServerError()) {
            log.error("请求处理失败 method={}, path={}, status={}, code={}, exception={}",
                    request.getMethod(), request.getRequestURI(), status.value(), code,
                    exception.getClass().getSimpleName(), exception);
        } else {
            // 不记录异常消息，避免将用户输入、SQL 或凭据写入日志。
            log.info("请求被拒绝 method={}, path={}, status={}, code={}, exception={}",
                    request.getMethod(), request.getRequestURI(), status.value(), code,
                    exception.getClass().getSimpleName());
        }
    }
}
