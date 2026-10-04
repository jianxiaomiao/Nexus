package com.nexus.application.exception;

public class ApplicationNotFoundException extends RuntimeException{
    public ApplicationNotFoundException(){
        super("未发现对应应用实例");
    }
}
