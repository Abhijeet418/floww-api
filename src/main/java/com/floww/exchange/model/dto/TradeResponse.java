package com.floww.exchange.model.dto;

import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TradeResponse {
    private UUID tradeId;
    private String ticker;
    private long price;
    private long qty;
    private UUID buyOrderId;
    private UUID sellOrderId;
    private Instant tradedAt;
}
