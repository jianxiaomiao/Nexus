package com.nexus.auth.exception;

public class InvalidAccessTokenException extends RuntimeException{
    public InvalidAccessTokenException(){
        super("访问令牌无效");
    }
}
