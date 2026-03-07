package com.floww.exchange.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "candle")
@IdClass(CandleId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Candle {
    @Id @Column(name = "ticker", length = 10) private String ticker;
    @Id @Column(name = "resolution", length = 5) private String resolution;
    @Id @Column(name = "bucket") private Instant bucket;

    @Column(name = "open", nullable = false) private long open;
    @Column(name = "high", nullable = false) private long high;
    @Column(name = "low", nullable = false)  private long low;
    @Column(name = "close", nullable = false) private long close;
    @Column(name = "volume", nullable = false) private long volume;
}
