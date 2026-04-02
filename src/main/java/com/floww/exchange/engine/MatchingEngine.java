package com.floww.exchange.engine;

import com.floww.exchange.engine.disruptor.DisruptorConfig;
import com.floww.exchange.engine.disruptor.TradeEventHolder;
import com.floww.exchange.exception.OrderRejectedException;
import com.floww.exchange.model.entity.Ticker;
import com.floww.exchange.model.dto.OrderBookSnapshot;
import com.floww.exchange.model.dto.OrderResponse;
import com.floww.exchange.model.enums.OrderStatus;
import com.floww.exchange.model.enums.OrderType;
import com.floww.exchange.model.event.OrderEvent;
import com.floww.exchange.model.event.TradeEvent;
import com.floww.exchange.service.OrderFutureRegistry;
import com.floww.exchange.service.RateLimitService;
import com.floww.exchange.service.SnapshotService;
import com.floww.exchange.service.TickerCache;
import com.lmax.disruptor.RingBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Matching Engine — the single-threaded brain of the exchange.
 *
 * Consumes OrderEvents from the Ingress Disruptor, matches via in-memory
 * OrderBooks, publishes TradeEvents to the Egress Disruptor, and completes
 * the CompletableFuture so the HTTP thread returns the authoritative result.
 *
 * The engine is the SOLE source of truth for order state.
 */
@Service
@Slf4j
public class MatchingEngine {

    private final RateLimitService rateLimitService;
    private final SnapshotService snapshotService;
    private final DisruptorConfig disruptorConfig;
    private final OrderFutureRegistry futureRegistry;
    private final TickerCache tickerCache;

    private final ConcurrentHashMap<String, OrderBook> orderBooks = new ConcurrentHashMap<>();

    public MatchingEngine(RateLimitService rateLimitService,
                          @Lazy SnapshotService snapshotService,
                          @Lazy DisruptorConfig disruptorConfig,
                          OrderFutureRegistry futureRegistry,
                          TickerCache tickerCache) {
        this.rateLimitService = rateLimitService;
        this.snapshotService = snapshotService;
        this.disruptorConfig = disruptorConfig;
        this.futureRegistry = futureRegistry;
        this.tickerCache = tickerCache;
    }

    /**
     * Called by OrderEventHandler on the Disruptor consumer thread.
     * Single-threaded — no locking needed on OrderBook.
     */
    public void onOrderEvent(OrderEvent event) {
        String ticker = event.getTicker();
        log.debug("[{}] {} orderId={} seq={}", ticker, event.getAction(), event.getOrderId(), event.getSequenceNumber());

        OrderBook book = getOrCreateBook(ticker);

        if (event.getAction() == OrderEvent.Action.CANCEL) {
            handleCancel(book, event);
        } else {
            handlePlace(book, event);
        }
    }

    private void handlePlace(OrderBook book, OrderEvent event) {
        // ── Circuit breaker: reject if ticker was halted after the gateway check ──
        if (tickerCache.isHalted(event.getTicker())) {
            rateLimitService.decrementOpenOrders(event.getAppId());
            futureRegistry.completeExceptionally(event.getOrderId(),
                    new OrderRejectedException("Ticker " + event.getTicker() + " is halted"));
            return;
        }

        OrderBookEntry entry = OrderBookEntry.builder()
                .orderId(event.getOrderId()).appId(event.getAppId())
                .traderId(event.getTraderId()).ticker(event.getTicker())
                .side(event.getSide()).orderType(event.getOrderType())
                .price(event.getPrice() != null ? event.getPrice() : 0)
                .qty(event.getQty()).remainingQty(event.getQty())
                .sequenceNumber(event.getSequenceNumber())
                .timestamp(System.currentTimeMillis())
                .build();

        List<TradeEvent> trades = book.processOrder(entry);

        // If matching triggered the circuit breaker, halt the ticker immediately
        if (book.isCircuitBreakerTriggered()) {
            book.setCircuitBreakerTriggered(false);
            tickerCache.haltTicker(event.getTicker(),
                    "Circuit breaker: price deviated >20%% from reference " + book.getReferencePrice());
        }

        // Publish each trade to the Egress Disruptor for async persistence
        RingBuffer<TradeEventHolder> tradeRing = disruptorConfig.getTradeRingBuffer();
        for (TradeEvent trade : trades) {
            long seq = tradeRing.next();
            try {
                TradeEventHolder holder = tradeRing.get(seq);
                holder.tradeId = trade.getTradeId();
                holder.ticker = trade.getTicker();
                holder.price = trade.getPrice();
                holder.qty = trade.getQty();
                holder.buyOrderId = trade.getBuyOrderId();
                holder.sellOrderId = trade.getSellOrderId();
                holder.buyerAppId = trade.getBuyerAppId();
                holder.sellerAppId = trade.getSellerAppId();
                holder.tradedAt = trade.getTradedAt();
                // Context for async order-status update
                holder.aggressorOrderId = event.getOrderId();
                holder.aggressorRemainingQty = entry.getRemainingQty();
                holder.aggressorOriginalQty = event.getQty();
                // Maker (passive) order context
                holder.makerOrderId = trade.getMakerOrderId();
                holder.makerRemainingQty = trade.getMakerRemainingQty();
                holder.makerOriginalQty = trade.getMakerOriginalQty();
                // Market data context
                holder.lastTradedPrice = book.getLastTradedPrice();
                holder.sessionVolume = book.getSessionVolume();
                holder.bestBid = !book.getBids().isEmpty() ? book.getBids().firstKey() : null;
                holder.bestAsk = !book.getAsks().isEmpty() ? book.getAsks().firstKey() : null;
            } finally {
                tradeRing.publish(seq);
            }
        }

        // Emit cancellation event for unfilled market orders so DB + rate limiter stay consistent
        if (event.getOrderType() == OrderType.MARKET && entry.getRemainingQty() > 0) {
            long cancelSeq = tradeRing.next();
            try {
                TradeEventHolder holder = tradeRing.get(cancelSeq);
                holder.cancelledOrderId = event.getOrderId();
                holder.cancelledOrderAppId = event.getAppId();
                holder.cancelledOrderOriginalQty = event.getQty();
                holder.cancelledOrderFilledQty = event.getQty() - entry.getRemainingQty();
                holder.ticker = event.getTicker();
            } finally {
                tradeRing.publish(cancelSeq);
            }
            log.debug("[{}] Market order {} partially filled {}/{}, remainder cancelled",
                    event.getTicker(), event.getOrderId(),
                    event.getQty() - entry.getRemainingQty(), event.getQty());
        }

        // ── Compute the authoritative status and complete the HTTP future ──
        long filledQty = event.getQty() - entry.getRemainingQty();
        OrderStatus status;
        if (entry.getRemainingQty() == 0) {
            status = OrderStatus.FILLED;
        } else if (event.getOrderType() == OrderType.MARKET) {
            // Market orders never rest — unfilled remainder is discarded
            status = OrderStatus.CANCELLED;
        } else if (filledQty > 0) {
            status = OrderStatus.PARTIALLY_FILLED;
        } else {
            status = OrderStatus.OPEN;
        }

        futureRegistry.complete(event.getOrderId(), OrderResponse.builder()
                .orderId(event.getOrderId())
                .clientOrderId(event.getClientOrderId())
                .ticker(event.getTicker())
                .side(event.getSide())
                .orderType(event.getOrderType())
                .price(event.getPrice())
                .qty(event.getQty())
                .filledQty(filledQty)
                .status(status)
                .sequenceNumber(event.getSequenceNumber())
                .createdAt(event.getTimestamp())
                .build());
    }

    private void handleCancel(OrderBook book, OrderEvent event) {
        OrderBookEntry cancelled = book.cancelOrder(event.getOrderId());
        if (cancelled != null) {
            rateLimitService.decrementOpenOrders(event.getAppId());
            log.debug("[{}] Order cancelled: {}", event.getTicker(), event.getOrderId());

            long filledQty = cancelled.getQty() - cancelled.getRemainingQty();
            futureRegistry.complete(event.getOrderId(), OrderResponse.builder()
                    .orderId(cancelled.getOrderId())
                    .ticker(cancelled.getTicker())
                    .side(cancelled.getSide())
                    .orderType(cancelled.getOrderType())
                    .price(cancelled.getPrice())
                    .qty(cancelled.getQty())
                    .filledQty(filledQty)
                    .status(OrderStatus.CANCELLED)
                    .sequenceNumber(cancelled.getSequenceNumber())
                    .createdAt(event.getTimestamp())
                    .build());
        } else {
            // Order not in book (already filled or already cancelled).
            // Complete with null — the gateway will check the DB for details.
            futureRegistry.complete(event.getOrderId(), null);
        }
    }

    private OrderBook getOrCreateBook(String ticker) {
        return orderBooks.computeIfAbsent(ticker, t -> {
            snapshotService.registerTicker(t);
            OrderBook newBook = new OrderBook(t);
            tickerCache.get(t)
                    .map(Ticker::getSessionOpenPrice)
                    .filter(p -> p > 0)
                    .ifPresent(newBook::setReferencePrice);
            return newBook;
        });
    }

    /**
     * Replay an order into the book without matching (for crash recovery).
     * Inserts directly at the given price level.
     */
    public void replayOrder(OrderBookEntry entry) {
        OrderBook book = getOrCreateBook(entry.getTicker());
        book.insertWithoutMatching(entry);
    }

    public OrderBookSnapshot getSnapshot(String ticker) {
        OrderBook book = orderBooks.get(ticker);
        return book != null ? book.toSnapshot() : null;
    }

    public OrderBook getOrderBook(String ticker) {
        return orderBooks.get(ticker);
    }
}
