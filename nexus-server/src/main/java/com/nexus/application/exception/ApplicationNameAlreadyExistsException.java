package com.nexus.application.exception;

import org.springframework.dao.DuplicateKeyException;

public class ApplicationNameAlreadyExistsException extends RuntimeException {
    public ApplicationNameAlreadyExistsException(Throwable cause){
        super("应用名重复",cause);
    }
}
