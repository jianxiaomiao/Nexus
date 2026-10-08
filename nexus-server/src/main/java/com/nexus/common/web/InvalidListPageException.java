package com.nexus.common.web;

public class InvalidListPageException extends RuntimeException {
    public InvalidListPageException() {
        super("页码必须大于等于1，每页条数必须在1到100之间");
    }
}
