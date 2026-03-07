package com.floww.exchange.model.dto;

import com.floww.exchange.model.enums.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderResponse {
    private UUID orderId;
    private String clientOrderId;
    private String ticker;
    private OrderSide side;
    private OrderType orderType;
    private Long price;
    private long qty;
    private long filledQty;
    private OrderStatus status;
    private Long sequenceNumber;
    private Instant createdAt;
}
