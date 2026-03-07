package com.floww.exchange.model.event;

import com.floww.exchange.model.enums.OrderSide;
import com.floww.exchange.model.enums.OrderType;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderEvent {
    public enum Action { PLACE, CANCEL }

    private Action action;
    private UUID orderId;
    private String clientOrderId;
    private UUID appId;
    private String traderId;
    private String ticker;
    private OrderSide side;
    private OrderType orderType;
    private Long price;
    private long qty;
    private long sequenceNumber;
    private Instant timestamp;
}
