package com.nexus.common.web;

import com.nexus.application.exception.ApplicationNameAlreadyExistsException;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.exception.InvalidApplicationIdException;
import com.nexus.application.exception.InvalidApplicationUpdateException;
import com.nexus.auth.exception.AccountForbiddenException;
import com.nexus.auth.exception.EmailAlreadyRegisteredException;
import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.exception.InvalidCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler{

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
    @ExceptionHandler(InvalidApplicationIdException.class)
    public ApiResponse<Void> handleInvalidApplicationIdException(InvalidApplicationIdException exception) {
        return new ApiResponse<>(
                "INVALID_APPLICATION_ID",
                exception.getMessage(),
                null
        );
    }
}
