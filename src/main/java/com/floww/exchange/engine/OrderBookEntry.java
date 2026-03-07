package com.floww.exchange.engine;

import com.floww.exchange.model.enums.OrderSide;
import com.floww.exchange.model.enums.OrderType;
import lombok.*;
import java.util.UUID;

/**
 * Lightweight in-memory order representation for the order book.
 * NOT a JPA entity — purely for matching engine use.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderBookEntry {
    private UUID orderId;
    private UUID appId;
    private String traderId;
    private String ticker;
    private OrderSide side;
    private OrderType orderType;
    private long price;
    private long qty;
    private long remainingQty;
    private long sequenceNumber;
    private long timestamp;
}
