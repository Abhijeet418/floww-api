package com.floww.exchange.engine.disruptor;

import java.time.Instant;
import java.util.UUID;

/**
 * Pre-allocated ring buffer slot for trade events produced by the matching engine.
 * Consumed asynchronously by the DB writer, OHLCV aggregator, and webhook dispatcher.
 */
public class TradeEventHolder {

    public UUID tradeId;
    public String ticker;
    public long price;
    public long qty;
    public UUID buyOrderId;
    public UUID sellOrderId;
    public UUID buyerAppId;
    public UUID sellerAppId;
    public Instant tradedAt;

    // Matching-engine context needed for async order-status updates
    public UUID aggressorOrderId;
    public long aggressorRemainingQty;
    public long aggressorOriginalQty;

    // Maker (passive/resting) order context
    public UUID makerOrderId;
    public long makerRemainingQty;
    public long makerOriginalQty;

    // Market data context
    public long lastTradedPrice;
    public long sessionVolume;
    public Long bestBid;
    public Long bestAsk;

    // Unfilled market order cancellation signal
    // When set, this event is not a trade but a cancellation of the unfilled remainder.
    public UUID cancelledOrderId;
    public UUID cancelledOrderAppId;
    public long cancelledOrderOriginalQty;
    public long cancelledOrderFilledQty;

    public void clear() {
        tradeId = null;
        ticker = null;
        price = 0;
        qty = 0;
        buyOrderId = null;
        sellOrderId = null;
        buyerAppId = null;
        sellerAppId = null;
        tradedAt = null;
        aggressorOrderId = null;
        aggressorRemainingQty = 0;
        aggressorOriginalQty = 0;
        makerOrderId = null;
        makerRemainingQty = 0;
        makerOriginalQty = 0;
        lastTradedPrice = 0;
        sessionVolume = 0;
        bestBid = null;
        bestAsk = null;
        cancelledOrderId = null;
        cancelledOrderAppId = null;
        cancelledOrderOriginalQty = 0;
        cancelledOrderFilledQty = 0;
    }
}
