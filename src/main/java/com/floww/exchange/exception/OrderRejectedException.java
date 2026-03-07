package com.floww.exchange.exception;

public class OrderRejectedException extends RuntimeException {
    public OrderRejectedException(String msg) { super(msg); }
}
