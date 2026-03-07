package com.floww.exchange.model.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MarketDataEvent {
    private String type;
    private String ticker;
    private Long price;
    private Long qty;
    private Long ltp;
    private Long volume;
    private Long bestBid;
    private Long bestAsk;
}
