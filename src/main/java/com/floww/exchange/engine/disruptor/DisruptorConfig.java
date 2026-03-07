package com.floww.exchange.engine.disruptor;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central Disruptor wiring for the exchange.
 *
 * Two ring buffers:
 *   1. orderRingBuffer  — Gateway publishes OrderEventHolders, MatchingEngine consumes
 *   2. tradeRingBuffer  — MatchingEngine publishes TradeEventHolders, consumed by:
 *        - AsyncTradePersistenceHandler (DB writes)
 *        - OhlcvAggregationHandler (candle updates)
 *        - WebhookDispatchHandler (webhook delivery)
 *        - MarketDataBroadcastHandler (SSE push)
 *
 * Ring buffer sizes are powers of 2 (Disruptor requirement).
 */
@Component
@Slf4j
public class DisruptorConfig {

    private static final int ORDER_BUFFER_SIZE = 1024 * 32;   // 32,768 slots  (~8MB, safe on 1GB RAM)
    private static final int TRADE_BUFFER_SIZE = 1024 * 32;   // 32,768 slots  (~8MB, safe on 1GB RAM)

    private Disruptor<OrderEventHolder> orderDisruptor;
    private Disruptor<TradeEventHolder> tradeDisruptor;

    private RingBuffer<OrderEventHolder> orderRingBuffer;
    private RingBuffer<TradeEventHolder> tradeRingBuffer;

    private final OrderEventHandler orderEventHandler;
    private final OrderPersistenceHandler orderPersistenceHandler;
    private final TradePersistenceHandler tradePersistenceHandler;
    private final OhlcvAggregationHandler ohlcvAggregationHandler;
    private final WebhookDispatchHandler webhookDispatchHandler;
    private final MarketDataBroadcastHandler marketDataBroadcastHandler;

    public DisruptorConfig(OrderEventHandler orderEventHandler,
                           OrderPersistenceHandler orderPersistenceHandler,
                           TradePersistenceHandler tradePersistenceHandler,
                           OhlcvAggregationHandler ohlcvAggregationHandler,
                           WebhookDispatchHandler webhookDispatchHandler,
                           MarketDataBroadcastHandler marketDataBroadcastHandler) {
        this.orderEventHandler = orderEventHandler;
        this.orderPersistenceHandler = orderPersistenceHandler;
        this.tradePersistenceHandler = tradePersistenceHandler;
        this.ohlcvAggregationHandler = ohlcvAggregationHandler;
        this.webhookDispatchHandler = webhookDispatchHandler;
        this.marketDataBroadcastHandler = marketDataBroadcastHandler;
    }

    @PostConstruct
    public void start() {
        // ── Order Disruptor ──
        // Multi-producer: HTTP threads publish concurrently.
        // Two parallel consumers:
        //   - orderEventHandler:       routes to the MatchingEngine
        //   - orderPersistenceHandler: async DB write (no DB on the HTTP hot path)
        // A ClearingHandler runs after both are done to reset the slot.
        orderDisruptor = new Disruptor<>(
                OrderEventHolder::new,
                ORDER_BUFFER_SIZE,
                namedThreadFactory("disruptor-order"),
                ProducerType.MULTI,
                new YieldingWaitStrategy()
        );
        orderDisruptor
                .handleEventsWith(orderEventHandler, orderPersistenceHandler)
                .then((holder, seq, eob) -> holder.clear());
        orderDisruptor.setDefaultExceptionHandler(new LoggingExceptionHandler<>("order"));
        orderRingBuffer = orderDisruptor.start();
        log.info("Order Disruptor started — buffer size: {}", ORDER_BUFFER_SIZE);

        // ── Trade Disruptor ──
        // Single producer: only the matching engine thread publishes
        tradeDisruptor = new Disruptor<>(
                TradeEventHolder::new,
                TRADE_BUFFER_SIZE,
                namedThreadFactory("disruptor-trade"),
                ProducerType.SINGLE,
                new YieldingWaitStrategy()
        );
        // All handlers consume in parallel (independent consumers)
        tradeDisruptor.handleEventsWith(
                tradePersistenceHandler,
                ohlcvAggregationHandler,
                webhookDispatchHandler,
                marketDataBroadcastHandler
        );
        tradeDisruptor.setDefaultExceptionHandler(new LoggingExceptionHandler<>("trade"));
        tradeRingBuffer = tradeDisruptor.start();
        log.info("Trade Disruptor started — buffer size: {}", TRADE_BUFFER_SIZE);
    }

    @PreDestroy
    public void stop() {
        if (orderDisruptor != null) orderDisruptor.shutdown();
        if (tradeDisruptor != null) tradeDisruptor.shutdown();
        log.info("Disruptors shut down");
    }

    public RingBuffer<OrderEventHolder> getOrderRingBuffer() {
        return orderRingBuffer;
    }

    public RingBuffer<TradeEventHolder> getTradeRingBuffer() {
        return tradeRingBuffer;
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Exception handler that logs but does not kill the Disruptor.
     */
    private static class LoggingExceptionHandler<T> implements com.lmax.disruptor.ExceptionHandler<T> {
        private final String name;

        LoggingExceptionHandler(String name) { this.name = name; }

        @Override
        public void handleEventException(Throwable ex, long sequence, T event) {
            log.error("[{}] Exception processing event seq={}: {}", name, sequence, ex.getMessage(), ex);
        }

        @Override
        public void handleOnStartException(Throwable ex) {
            log.error("[{}] Exception during startup: {}", name, ex.getMessage(), ex);
        }

        @Override
        public void handleOnShutdownException(Throwable ex) {
            log.error("[{}] Exception during shutdown: {}", name, ex.getMessage(), ex);
        }
    }
}
