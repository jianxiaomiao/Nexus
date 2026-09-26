package com.nexus.auth.exception;

public class InvalidAccessTokenException extends RuntimeException{
    public InvalidAccessTokenException(){
        super("jwt解析错误");
    }
}
