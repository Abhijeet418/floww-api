package com.floww.exchange.model.dto;

import lombok.*;
import java.time.Instant;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class OrderBookSnapshot {
    private String ticker;
    private List<PriceLevel> bids;
    private List<PriceLevel> asks;
    private Instant timestamp;
    private String exchangeType;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PriceLevel {
        private long price;
        private long qty;
        private int orderCount;
    }
}
