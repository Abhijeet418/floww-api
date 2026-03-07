package com.floww.exchange.exception;

public class AppNotAuthorizedException extends RuntimeException {
    public AppNotAuthorizedException(String msg) { super(msg); }
}
