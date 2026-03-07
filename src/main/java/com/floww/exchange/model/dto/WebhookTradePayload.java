package com.floww.exchange.model.dto;

import lombok.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound webhook payload sent to each broker for their own fills.
 * Contains only the information relevant to the receiving side —
 * counterparty identity and internal matching-engine fields are intentionally excluded.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WebhookTradePayload {
    private UUID tradeId;
    private String ticker;
    private long price;
    private long qty;
    /** The receiving app's own order that was filled. */
    private UUID orderId;
    /** BUY or SELL — the receiving app's side in this trade. */
    private String side;
    private Instant tradedAt;
}
