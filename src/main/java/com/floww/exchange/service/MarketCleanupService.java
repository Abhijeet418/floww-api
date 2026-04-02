package com.floww.exchange.service;

import com.floww.exchange.engine.MatchingEngine;
import com.floww.exchange.engine.OrderBook;
import com.floww.exchange.engine.OrderBookEntry;
import com.floww.exchange.engine.disruptor.DisruptorConfig;
import com.floww.exchange.engine.disruptor.OrderEventHolder;
import com.lmax.disruptor.RingBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Handles end-of-day maintenance for the exchange.
 *
 * At market close, publishes CANCEL events through the Disruptor for every
 * resting order, ensuring the engine and the database stay in sync.
 * The previous raw-SQL approach caused a split-brain where the DB said orders
 * were cancelled but the engine still held them in memory.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketCleanupService {

    private final MatchingEngine matchingEngine;
    private final DisruptorConfig disruptorConfig;
    private final SnapshotService snapshotService;

    // Runs at 10:31 UTC Monday-Friday (1 minute after market close)
    @Scheduled(cron = "0 31 9 * * MON-FRI", zone = "UTC")
    public void performEndOfDayCleanup() {
        log.info("Starting Market Close Cleanup: Cancelling all resting orders via Disruptor...");

        Set<String> tickers = snapshotService.getKnownTickers();
        RingBuffer<OrderEventHolder> ringBuffer = disruptorConfig.getOrderRingBuffer();
        int cancelledCount = 0;

        for (String ticker : tickers) {
            OrderBook book = matchingEngine.getOrderBook(ticker);
            if (book == null) continue;

            List<OrderBookEntry> restingOrders = book.getAllRestingOrders();
            for (OrderBookEntry entry : restingOrders) {
                long sequence = ringBuffer.next();
                try {
                    OrderEventHolder holder = ringBuffer.get(sequence);
                    holder.action    = OrderEventHolder.Action.CANCEL;
                    holder.orderId   = entry.getOrderId();
                    holder.ticker    = ticker;
                    holder.appId     = entry.getAppId();
                    holder.timestamp = Instant.now();
                } finally {
                    ringBuffer.publish(sequence);
                }
                cancelledCount++;
            }
        }

        log.info("Cleanup complete. {} cancel events published to Disruptor.", cancelledCount);
    }

    /**
     * Manual trigger for testing via Admin API or startup
     */
    public void manualCleanup() {
        performEndOfDayCleanup();
    }
}
