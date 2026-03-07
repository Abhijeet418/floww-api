package com.floww.exchange.model.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TickerResponse {
    private String symbol;
    private String name;
    private String status;
    private long lotSize;
    private Long sessionOpenPrice;
    private String exchangeType;
}
