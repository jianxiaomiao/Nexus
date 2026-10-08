package com.nexus.common.web;

import com.nexus.application.exception.ApplicationNameAlreadyExistsException;
import com.nexus.application.exception.ApplicationDisabledException;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.exception.InvalidApplicationIdException;
import com.nexus.application.exception.InvalidApplicationUpdateException;
import com.nexus.apikey.exception.ApiKeyNameAlreadyExistsException;
import com.nexus.apikey.exception.ApiKeyNotFoundException;
import com.nexus.apikey.exception.InvalidApiKeyDeleteException;
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

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidListPageException.class)
    public ApiResponse<Void> handleInvalidListPage(InvalidListPageException exception) {
        return new ApiResponse<>("INVALID_LIST_PAGE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidUsagePaginationException.class)
    public ApiResponse<Void> handleInvalidUsagePagination(InvalidUsagePaginationException exception) {
        return new ApiResponse<>("INVALID_USAGE_PAGINATION", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidUsageTimeRangeException.class)
    public ApiResponse<Void> handleInvalidUsageTimeRange(InvalidUsageTimeRangeException exception) {
        return new ApiResponse<>("INVALID_USAGE_TIME_RANGE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidShortLinkRequestException.class)
    public ApiResponse<Void> handleInvalidShortLinkRequest(InvalidShortLinkRequestException exception) {
        return new ApiResponse<>("INVALID_SHORT_LINK_REQUEST", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(ShortLinkNotFoundException.class)
    public ApiResponse<Void> handleShortLinkNotFound(ShortLinkNotFoundException exception) {
        return new ApiResponse<>("SHORT_LINK_NOT_FOUND", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.GONE)
    @ExceptionHandler(ShortLinkExpiredException.class)
    public ApiResponse<Void> handleShortLinkExpired(ShortLinkExpiredException exception) {
        return new ApiResponse<>("SHORT_LINK_EXPIRED", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(ShortCodeExhaustedException.class)
    public ApiResponse<Void> handleShortCodeExhausted(ShortCodeExhaustedException exception) {
        return new ApiResponse<>("SHORT_CODE_UNAVAILABLE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadableRequestBody(HttpMessageNotReadableException exception) {
        // JSON 转换发生在 Controller 与 Bean Validation 之前，不向客户端回显原始输入或底层异常。
        return new ApiResponse<>("INVALID_REQUEST_BODY", "请求体格式错误或字段值无效", null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HashContentRequiredException.class)
    public ApiResponse<Void> handleHashContentRequired(HashContentRequiredException exception) {
        return new ApiResponse<>("HASH_CONTENT_REQUIRED", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HashAlgorithmRequiredException.class)
    public ApiResponse<Void> handleHashAlgorithmRequired(HashAlgorithmRequiredException exception) {
        return new ApiResponse<>("HASH_ALGORITHM_REQUIRED", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HashInputTooLargeException.class)
    public ApiResponse<Void> handleHashInputTooLarge(HashInputTooLargeException exception) {
        return new ApiResponse<>("HASH_INPUT_TOO_LARGE", exception.getMessage(), null);
    }

    @ExceptionHandler(InvalidApiKeyCredentialException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidApiKeyCredential(InvalidApiKeyCredentialException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"nexus-openapi\"")
                .body(new ApiResponse<>("INVALID_API_KEY_CREDENTIAL", exception.getMessage(), null));
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(ApiKeyForbiddenException.class)
    public ApiResponse<Void> handleApiKeyForbidden(ApiKeyForbiddenException exception) {
        return new ApiResponse<>("API_KEY_FORBIDDEN", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ApiResponse<Void> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException exception) {
        // 这里由你构造并返回错误 ApiResponse
        return new ApiResponse<>(
                "AUTH_EMAIL_ALREADY_REGISTERED",
                exception.getMessage(),
                null
        );
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Map<String, String>> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError fieldError
                : exception.getBindingResult().getFieldErrors()) {

            fieldErrors.putIfAbsent(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            );
        }

        return new ApiResponse<>(
                "VALIDATION_ERROR",
                "请求参数校验失败",
                fieldErrors
        );
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(InvalidCredentialsException.class)
    public ApiResponse<Void> handleInvalidCredentialsException(InvalidCredentialsException exception) {
        // 这里由你构造并返回错误 ApiResponse
        return new ApiResponse<>(
                "AUTH_INVALID_CREDENTIALS",
                exception.getMessage(),
                null
        );
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(AccountForbiddenException.class)
    public ApiResponse<Void> handleAccountForbiddenException(AccountForbiddenException exception) {
        // 这里由你构造并返回错误 ApiResponse
        return new ApiResponse<>(
                "AUTH_ACCOUNT_FORBIDDEN",
                exception.getMessage(),
                null
        );
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ApplicationNameAlreadyExistsException.class)
    public ApiResponse<Void> handleApplicationNameAlreadyExistsException(ApplicationNameAlreadyExistsException exception){
        return new ApiResponse<>(
                "APPLICATION_NAME_ALREADY_EXISTS",
                exception.getMessage(),
                null
        );
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(InvalidAccessTokenException.class)
    public ApiResponse<Void> handleInvalidAccessTokenException(InvalidAccessTokenException exception){
        return new ApiResponse<>(
                "INVALID_ACCESS_TOKEN",
                exception.getMessage(),
                null
        );
    }
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(ApplicationNotFoundException.class)
    public ApiResponse<Void> handleApplicationNotFoundException(ApplicationNotFoundException exception){
        return new ApiResponse<>(
                "APPLICATION_NOT_FOUND",
                exception.getMessage(),
                null
        );
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ApplicationDisabledException.class)
    public ApiResponse<Void> handleApplicationDisabledException(ApplicationDisabledException exception) {
        return new ApiResponse<>(
                "APPLICATION_DISABLED",
                exception.getMessage(),
                null
        );
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(ApiKeyNameAlreadyExistsException.class)
    public ApiResponse<Void> handleApiKeyNameAlreadyExistsException(ApiKeyNameAlreadyExistsException exception) {
        return new ApiResponse<>(
                "API_KEY_NAME_ALREADY_EXISTS",
                exception.getMessage(),
                null
        );
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidApplicationUpdateException.class)
    public ApiResponse<Void> handleInvalidApplicationUpdateException(InvalidApplicationUpdateException exception) {
        return new ApiResponse<>(
                "INVALID_APPLICATION_UPDATE",
                exception.getMessage(),
                null
        );
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidApiKeyDeleteException.class)
    public ApiResponse<Void> handleInvalidApiKeyDeleteException(InvalidApiKeyDeleteException exception) {
        return new ApiResponse<>("INVALID_API_KEY_DELETE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidApiKeyUpdateException.class)
    public ApiResponse<Void> handleInvalidApiKeyUpdateException(InvalidApiKeyUpdateException exception) {
        return new ApiResponse<>("INVALID_API_KEY_UPDATE", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(ApiKeyNotFoundException.class)
    public ApiResponse<Void> handleApiKeyNotFoundException(ApiKeyNotFoundException exception) {
        return new ApiResponse<>("API_KEY_NOT_FOUND", exception.getMessage(), null);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(InvalidApplicationIdException.class)
    public ApiResponse<Void> handleInvalidApplicationIdException(InvalidApplicationIdException exception) {
        return new ApiResponse<>(
                "INVALID_APPLICATION_ID",
                exception.getMessage(),
                null
        );
    }
}
