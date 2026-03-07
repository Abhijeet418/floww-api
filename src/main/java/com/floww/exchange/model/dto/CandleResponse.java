package com.floww.exchange.model.dto;

import lombok.*;
import java.time.Instant;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CandleResponse {
    private String ticker;
    private String resolution;
    private Instant bucket;
    private long open;
    private long high;
    private long low;
    private long close;
    private long volume;
}
