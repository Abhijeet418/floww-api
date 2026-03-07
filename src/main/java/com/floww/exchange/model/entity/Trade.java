package com.floww.exchange.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trade")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Trade {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "trade_id")
    private UUID tradeId;

    @Column(name = "ticker", nullable = false, length = 10)
    private String ticker;

    @Column(name = "price", nullable = false)
    private long price;

    @Column(name = "qty", nullable = false)
    private long qty;

    @Column(name = "buy_order_id", nullable = false)
    private UUID buyOrderId;

    @Column(name = "sell_order_id", nullable = false)
    private UUID sellOrderId;

    @Column(name = "buyer_app_id", nullable = false)
    private UUID buyerAppId;

    @Column(name = "seller_app_id", nullable = false)
    private UUID sellerAppId;

    @Column(name = "traded_at", nullable = false)
    private Instant tradedAt;

    @PrePersist
    void onCreate() { if (tradedAt == null) tradedAt = Instant.now(); }
}
