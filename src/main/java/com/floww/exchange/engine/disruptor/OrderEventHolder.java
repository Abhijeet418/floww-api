package com.floww.exchange.engine.disruptor;

import com.floww.exchange.model.enums.OrderSide;
import com.floww.exchange.model.enums.OrderType;

import java.time.Instant;
import java.util.UUID;

/**
 * Pre-allocated ring buffer slot for order events.
 * Mutable — the Disruptor reuses these objects to avoid GC pressure.
 * Fields are set by the publisher and read by the consumer.
 */
public class OrderEventHolder {

    public enum Action { PLACE, CANCEL }

    // ── Fields (public for zero-copy access on the hot path) ──
    public Action action;
    public UUID orderId;
    public String clientOrderId;
    public UUID appId;
    public String traderId;
    public String ticker;
    public OrderSide side;
    public OrderType orderType;
    public Long price;
    public long qty;
    public long sequenceNumber;
    public Instant timestamp;

    public void clear() {
        action = null;
        orderId = null;
        clientOrderId = null;
        appId = null;
        traderId = null;
        ticker = null;
        side = null;
        orderType = null;
        price = null;
        qty = 0;
        sequenceNumber = 0;
        timestamp = null;
    }
}
