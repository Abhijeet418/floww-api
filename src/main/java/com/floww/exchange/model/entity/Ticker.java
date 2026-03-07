package com.floww.exchange.model.entity;

import com.floww.exchange.model.enums.TickerStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticker")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Ticker {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "ticker_id")
    private UUID tickerId;

    @Column(name = "symbol", nullable = false, unique = true, length = 10)
    private String symbol;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private TickerStatus status;

    @Column(name = "lot_size", nullable = false)
    private long lotSize;

    @Column(name = "session_open_price")
    private Long sessionOpenPrice;

    @Column(name = "halt_reason", columnDefinition = "text")
    private String haltReason;

    @Column(name = "halted_until")
    private Instant haltedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); if (status == null) status = TickerStatus.ACTIVE; }
}
