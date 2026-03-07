package com.floww.exchange.model.event;

import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TradeEvent {
    private UUID tradeId;
    private String ticker;
    private long price;
    private long qty;
    private UUID buyOrderId;
    private UUID sellOrderId;
    private UUID buyerAppId;
    private UUID sellerAppId;
    private Instant tradedAt;

    // Matching-engine context needed for async maker order-status updates
    private UUID makerOrderId;
    private long makerRemainingQty;
    private long makerOriginalQty;
}
