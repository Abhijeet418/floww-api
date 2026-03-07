package com.floww.exchange.exception;

public class DuplicateOrderException extends RuntimeException {
    public DuplicateOrderException(String msg) { super(msg); }
}
